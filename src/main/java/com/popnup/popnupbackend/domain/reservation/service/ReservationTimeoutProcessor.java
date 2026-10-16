package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutProcessor {

  private static final String PAYMENT_TIMEOUT_LABEL = "결제 미완료 만료";
  private static final String NO_SHOW_LABEL = "지난 회차 미방문 만료";

  private final ReservationRepository reservationRepository;
  private final ReservationCancelManager reservationCancelManager;
  private final ReservationChunkClaimer reservationChunkClaimer;
  private final MeterRegistry meterRegistry;

  @Value("${reservation.timeout.chunk-size:100}")
  private int chunkSize;

  @Value("${reservation.timeout.worker-count:1}")
  private int workerCount;

  @Value("${reservation.timeout.payment-timeout-seconds:600}")
  private long paymentTimeoutSeconds;

  public void payTimeOut() {
    LocalDateTime deadLine = LocalDateTime.now().minusSeconds(paymentTimeoutSeconds);
    process(
        PAYMENT_TIMEOUT_LABEL,
        () ->
            reservationRepository.findPendingReservationsChunk(
                ReservationStatus.PENDING, deadLine, chunkSize),
        () ->
            reservationRepository.findPendingReservationsChunkForUpdate(
                ReservationStatus.PENDING, deadLine, chunkSize),
        reservationCancelManager::expirePaymentTimeout);
  }

  public void expirePastReservations() {
    LocalDateTime now = LocalDateTime.now();
    LocalDate today = now.toLocalDate();
    LocalTime nowTime = now.toLocalTime();
    process(
        NO_SHOW_LABEL,
        () -> reservationRepository.findExpiredReservationsChunk(today, nowTime, chunkSize),
        () ->
            reservationRepository.findExpiredReservationsChunkForUpdate(today, nowTime, chunkSize),
        reservationCancelManager::expireNoShow);
  }

  private void process(
      String label,
      Supplier<List<Reservation>> chunkSupplier,
      Supplier<List<Reservation>> lockedChunkSupplier,
      Function<Long, Boolean> expireAction) {
    if (workerCount <= 1) {
      processInChunks(label, chunkSupplier, expireAction);
    } else {
      processInChunksMultiWorker(label, lockedChunkSupplier, expireAction);
    }
  }

  private void processInChunks(
      String label,
      Supplier<List<Reservation>> chunkSupplier,
      Function<Long, Boolean> expireAction) {
    Totals totals = new Totals();
    int chunkNumber = 0;

    log.debug("[{}] 청크 기반 처리 시작 (Chunk Size: {})", label, chunkSize);

    while (true) {
      long chunkStart = System.currentTimeMillis();
      List<Reservation> chunk = chunkSupplier.get();
      if (chunk.isEmpty()) {
        break;
      }
      chunkNumber++;

      totals.add(processChunk(label, label, chunk, expireAction));

      long chunkElapsedMs = System.currentTimeMillis() - chunkStart;
      log.debug(
          "[{}] 청크 #{} 처리 완료 - {}건, {}ms (건당 평균 {}ms)",
          label,
          chunkNumber,
          chunk.size(),
          chunkElapsedMs,
          String.format("%.2f", (double) chunkElapsedMs / chunk.size()));

      if (chunk.size() < chunkSize) {
        break;
      }
    }

    logTotals(label, "처리 완료", totals);
  }

  private void processInChunksMultiWorker(
      String label,
      Supplier<List<Reservation>> lockedChunkSupplier,
      Function<Long, Boolean> expireAction) {
    Totals totals = new Totals();

    log.debug(
        "[{}] Multi Worker 처리 시작 (Worker: {}, Chunk Size: {})", label, workerCount, chunkSize);

    ExecutorService executor =
        Executors.newFixedThreadPool(
            workerCount,
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setName("expiry-worker-" + thread.getId());
              return thread;
            });

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < workerCount; i++) {
      futures.add(
          executor.submit(() -> workerLoop(label, lockedChunkSupplier, expireAction, totals)));
    }

    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        log.error("[{}] Worker 실행 중 예외 발생", label, e);
      }
    }
    executor.shutdown();

    logTotals(label, "Multi Worker 처리 완료", totals);
  }

  private void workerLoop(
      String label,
      Supplier<List<Reservation>> lockedChunkSupplier,
      Function<Long, Boolean> expireAction,
      Totals totals) {
    String workerName = Thread.currentThread().getName();

    while (true) {
      long chunkStart = System.currentTimeMillis();
      List<Reservation> chunk = reservationChunkClaimer.claim(lockedChunkSupplier);
      if (chunk.isEmpty()) {
        break;
      }

      ChunkResult result = processChunk(label, label + "][" + workerName, chunk, expireAction);
      totals.add(result);

      long chunkElapsedMs = System.currentTimeMillis() - chunkStart;
      log.debug(
          "[{}][{}] 청크 처리 완료 - {}건(성공 {}, no-op {}, 실패 {}), {}ms",
          label,
          workerName,
          chunk.size(),
          result.processed(),
          result.skipped(),
          result.failed(),
          chunkElapsedMs);
    }
  }

  private ChunkResult processChunk(
      String label, String logTag, List<Reservation> chunk, Function<Long, Boolean> expireAction) {
    int processed = 0;
    int skipped = 0;
    int failed = 0;

    for (Reservation target : chunk) {
      try {
        if (expireAction.apply(target.getId())) {
          processed++;
        } else {
          skipped++;
        }
      } catch (Exception e) {
        failed++;
        meterRegistry.counter("reservation.expire.failure", "label", label).increment();
        log.error(
            "[{}] 예약 단건 처리 실패 - 다음 사이클에 재조회되어 재시도됩니다. reservationId={}", logTag, target.getId(), e);
      }
    }
    return new ChunkResult(processed, skipped, failed);
  }

  private void logTotals(String label, String title, Totals totals) {
    if (totals.hasAny()) {
      log.info(
          "[{}] {} (성공 {}건, no-op {}건, 실패 {}건)",
          label,
          title,
          totals.processed.get(),
          totals.skipped.get(),
          totals.failed.get());
    }
  }

  private record ChunkResult(int processed, int skipped, int failed) {}

  private static final class Totals {
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    void add(ChunkResult result) {
      processed.addAndGet(result.processed());
      skipped.addAndGet(result.skipped());
      failed.addAndGet(result.failed());
    }

    boolean hasAny() {
      return processed.get() > 0 || skipped.get() > 0 || failed.get() > 0;
    }
  }
}
