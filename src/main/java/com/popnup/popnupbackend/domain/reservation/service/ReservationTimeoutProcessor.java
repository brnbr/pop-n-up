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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutProcessor {

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
    if (workerCount <= 1) {
      processInChunks(
          "결제 미완료 만료",
          () ->
              reservationRepository.findPendingReservationsChunk(
                  ReservationStatus.PENDING, deadLine, chunkSize),
          reservationId -> reservationCancelManager.expirePaymentTimeout(reservationId));
    } else {
      processInChunksMultiWorker(
          "결제 미완료 만료",
          () ->
              reservationRepository.findPendingReservationsChunkForUpdate(
                  ReservationStatus.PENDING, deadLine, chunkSize),
          reservationId -> reservationCancelManager.expirePaymentTimeout(reservationId));
    }
  }

  public void expirePastReservations() {
    LocalDate today = LocalDate.now();
    LocalTime nowTime = LocalTime.now();
    if (workerCount <= 1) {
      processInChunks(
          "지난 회차 미방문 만료",
          () -> reservationRepository.findExpiredReservationsChunk(today, nowTime, chunkSize),
          reservationId -> reservationCancelManager.expireNoShow(reservationId));
    } else {
      processInChunksMultiWorker(
          "지난 회차 미방문 만료",
          () ->
              reservationRepository.findExpiredReservationsChunkForUpdate(
                  today, nowTime, chunkSize),
          reservationId -> reservationCancelManager.expireNoShow(reservationId));
    }
  }

  private void processInChunks(
      String label,
      Supplier<List<Reservation>> chunkSupplier,
      Function<Long, Boolean> expireAction) {
    int totalProcessed = 0;
    int totalSkipped = 0;
    int totalFailed = 0;
    int chunkNumber = 0;
    log.info("[{}] 청크 기반 처리 시작 (Chunk Size: {})", label, chunkSize);

    while (true) {
      long chunkStart = System.currentTimeMillis();
      List<Reservation> chunk = chunkSupplier.get();

      if (chunk.isEmpty()) {
        break;
      }

      chunkNumber++;
      for (Reservation target : chunk) {
        try {
          boolean changed = expireSingleReservation(target.getId(), expireAction);
          if (changed) {
            totalProcessed++;
          } else {
            totalSkipped++;
          }
        } catch (Exception e) {
          totalFailed++;
          meterRegistry.counter("reservation.expire.failure", "label", label).increment();
          log.error(
              "[{}] 예약 단건 처리 실패 - 다음 사이클에 재조회되어 재시도됩니다. reservationId={}",
              label,
              target.getId(),
              e);
        }
      }
      long chunkElapsedMs = System.currentTimeMillis() - chunkStart;
      log.info(
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

    if (totalProcessed > 0 || totalSkipped > 0 || totalFailed > 0) {
      log.info(
          "[{}] 처리 완료 (성공 {}건, no-op {}건, 실패 {}건)",
          label,
          totalProcessed,
          totalSkipped,
          totalFailed);
    }
  }

  private void processInChunksMultiWorker(
      String label,
      Supplier<List<Reservation>> lockedChunkSupplier,
      Function<Long, Boolean> expireAction) {

    AtomicInteger totalProcessed = new AtomicInteger();
    AtomicInteger totalSkipped = new AtomicInteger();
    AtomicInteger totalFailed = new AtomicInteger();
    log.info("[{}] Multi Worker 처리 시작 (Worker: {}, Chunk Size: {})", label, workerCount, chunkSize);

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
          executor.submit(
              () ->
                  workerLoop(
                      label,
                      lockedChunkSupplier,
                      expireAction,
                      totalProcessed,
                      totalSkipped,
                      totalFailed)));
    }

    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        log.error("[{}] Worker 실행 중 예외 발생", label, e);
      }
    }
    executor.shutdown();

    if (totalProcessed.get() > 0 || totalSkipped.get() > 0 || totalFailed.get() > 0) {
      log.info(
          "[{}] Multi Worker 처리 완료 (성공 {}건, no-op {}건, 실패 {}건)",
          label,
          totalProcessed.get(),
          totalSkipped.get(),
          totalFailed.get());
    }
  }

  private void workerLoop(
      String label,
      Supplier<List<Reservation>> lockedChunkSupplier,
      Function<Long, Boolean> expireAction,
      AtomicInteger totalProcessed,
      AtomicInteger totalSkipped,
      AtomicInteger totalFailed) {

    String workerName = Thread.currentThread().getName();

    while (true) {
      long chunkStart = System.currentTimeMillis();
      List<Reservation> chunk = reservationChunkClaimer.claim(lockedChunkSupplier);

      if (chunk.isEmpty()) {
        break;
      }

      int chunkSuccess = 0;
      int chunkSkipped = 0;
      int chunkFail = 0;
      for (Reservation target : chunk) {
        try {
          boolean changed = expireSingleReservation(target.getId(), expireAction);
          if (changed) {
            chunkSuccess++;
            totalProcessed.incrementAndGet();
          } else {
            chunkSkipped++;
            totalSkipped.incrementAndGet();
          }
        } catch (Exception e) {
          chunkFail++;
          totalFailed.incrementAndGet();
          meterRegistry.counter("reservation.expire.failure", "label", label).increment();
          log.error("[{}][{}] 예약 단건 처리 실패. reservationId={}", label, workerName, target.getId(), e);
        }
      }
      long chunkElapsedMs = System.currentTimeMillis() - chunkStart;
      log.info(
          "[{}][{}] 청크 처리 완료 - {}건(성공 {}, no-op {}, 실패 {}), {}ms",
          label,
          workerName,
          chunk.size(),
          chunkSuccess,
          chunkSkipped,
          chunkFail,
          chunkElapsedMs);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean expireSingleReservation(Long reservationId, Function<Long, Boolean> expireAction) {
    return expireAction.apply(reservationId);
  }
}
