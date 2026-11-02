package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class ReservationTimeoutProcessor {

  public enum ExpiryMode {
    CHUNK,
    PER_ITEM
  }

  public record ExpiryResult(
      ExpiryMode mode,
      int chunkSize,
      int workers,
      int processed,
      int skipped,
      int failed,
      long elapsedMs,
      double maxTxMs) {}

  private static final String PAYMENT_TIMEOUT_LABEL = "결제 미완료 만료";
  private static final String NO_SHOW_LABEL = "지난 회차 미방문 만료";
  private static final String TX_TIMER = "reservation.expire.tx";

  private final ReservationRepository reservationRepository;
  private final ReservationCancelManager reservationCancelManager;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate chunkTransaction;

  @Value("${reservation.timeout.chunk-size:100}")
  private int chunkSize;

  @Value("${reservation.timeout.workers:1}")
  private int workers;

  @Value("${reservation.timeout.expiry-mode:CHUNK}")
  private ExpiryMode expiryMode;

  @Value("${reservation.timeout.payment-timeout-seconds:600}")
  private long paymentTimeoutSeconds;

  public ReservationTimeoutProcessor(
      ReservationRepository reservationRepository,
      ReservationCancelManager reservationCancelManager,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager) {
    this.reservationRepository = reservationRepository;
    this.reservationCancelManager = reservationCancelManager;
    this.meterRegistry = meterRegistry;
    this.chunkTransaction = new TransactionTemplate(transactionManager);
    this.chunkTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public ExpiryResult expireUnpaidReservations() {
    return expireUnpaidReservations(expiryMode, chunkSize, workers);
  }

  public ExpiryResult expireUnpaidReservations(ExpiryMode mode, int size, int workerCount) {
    ExpiryRun run =
        new ExpiryRun(
            LocalDateTime.now().minusSeconds(paymentTimeoutSeconds),
            mode == null ? ExpiryMode.CHUNK : mode,
            Math.max(1, size));
    int threads = Math.max(1, workerCount);
    long started = System.nanoTime();

    log.debug(
        "[{}] 처리 시작 (방식: {}, 청크: {}, 워커: {})",
        PAYMENT_TIMEOUT_LABEL,
        run.mode,
        run.chunkSize,
        threads);

    if (threads == 1) {
      drain(run);
    } else {
      drainWithWorkers(run, threads);
    }

    ExpiryResult result =
        new ExpiryResult(
            run.mode,
            run.chunkSize,
            threads,
            run.processed.get(),
            run.skipped.get(),
            run.failed.get(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            run.maxTxNanos.get() / 1_000_000.0);
    if (run.total() > 0) {
      log.info(
          "[{}] 처리 완료 (성공 {}건, no-op {}건, 실패 {}건, {}ms, 최대 트랜잭션 {}ms)",
          PAYMENT_TIMEOUT_LABEL,
          result.processed(),
          result.skipped(),
          result.failed(),
          result.elapsedMs(),
          String.format("%.1f", result.maxTxMs()));
    }
    return result;
  }

  private void drainWithWorkers(ExpiryRun run, int workerCount) {
    ExecutorService pool = Executors.newFixedThreadPool(workerCount);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < workerCount; i++) {
        futures.add(pool.submit(() -> drain(run)));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      log.error("[{}] 워커 실행 실패", PAYMENT_TIMEOUT_LABEL, e.getCause());
    } finally {
      pool.shutdown();
    }
  }

  private void drain(ExpiryRun run) {
    int claimed;
    do {
      claimed = run.mode == ExpiryMode.PER_ITEM ? expireItemByItem(run) : expireChunk(run);
    } while (claimed == run.chunkSize);
  }

  private int expireChunk(ExpiryRun run) {
    long chunkStart = System.currentTimeMillis();
    List<Long> claimedIds = new ArrayList<>();
    try {
      timed(
          run,
          "chunk",
          () -> {
            chunkTransaction.executeWithoutResult(
                status -> {
                  List<Reservation> targets = claim(run);
                  targets.forEach(target -> claimedIds.add(target.getId()));
                  if (!targets.isEmpty()) {
                    reservationCancelManager.expirePaymentTimeoutChunk(targets);
                  }
                });
            return null;
          });
      run.processed.addAndGet(claimedIds.size());
    } catch (Exception e) {
      meterRegistry
          .counter("reservation.expire.chunk.fallback", "label", PAYMENT_TIMEOUT_LABEL)
          .increment();
      log.warn(
          "[{}] 청크 처리 실패 - 이 청크만 건별로 다시 처리합니다. 건수={}", PAYMENT_TIMEOUT_LABEL, claimedIds.size(), e);
      expireEach(run, claimedIds);
    }
    logChunk(claimedIds.size(), chunkStart);
    return claimedIds.size();
  }

  private int expireItemByItem(ExpiryRun run) {
    long chunkStart = System.currentTimeMillis();
    List<Long> claimedIds =
        timed(
            run,
            "claim",
            () ->
                chunkTransaction.execute(
                    status -> claim(run).stream().map(Reservation::getId).toList()));
    expireEach(run, claimedIds);
    logChunk(claimedIds.size(), chunkStart);
    return claimedIds.size();
  }

  private List<Reservation> claim(ExpiryRun run) {
    return reservationRepository.claimExpiredPendingChunk(
        run.deadline, List.copyOf(run.givenUp), run.chunkSize);
  }

  private void expireEach(ExpiryRun run, List<Long> reservationIds) {
    for (Long reservationId : reservationIds) {
      try {
        if (timed(
            run, "item", () -> reservationCancelManager.expirePaymentTimeout(reservationId))) {
          run.processed.incrementAndGet();
        } else {
          run.skipped.incrementAndGet();
        }
      } catch (Exception e) {
        run.failed.incrementAndGet();
        run.givenUp.add(reservationId);
        meterRegistry
            .counter("reservation.expire.failure", "label", PAYMENT_TIMEOUT_LABEL)
            .increment();
        log.error(
            "[{}] 예약 단건 처리 실패 - 다음 사이클에 재조회되어 재시도됩니다. reservationId={}",
            PAYMENT_TIMEOUT_LABEL,
            reservationId,
            e);
      }
    }
  }

  private <T> T timed(ExpiryRun run, String kind, Supplier<T> transaction) {
    long started = System.nanoTime();
    try {
      return transaction.get();
    } finally {
      long elapsed = System.nanoTime() - started;
      meterRegistry.timer(TX_TIMER, "kind", kind).record(elapsed, TimeUnit.NANOSECONDS);
      run.maxTxNanos.accumulateAndGet(elapsed, Math::max);
    }
  }

  private void logChunk(int size, long chunkStart) {
    if (size == 0) {
      return;
    }
    long elapsedMs = System.currentTimeMillis() - chunkStart;
    log.debug(
        "[{}] 청크 처리 완료 - {}건, {}ms (건당 평균 {}ms)",
        PAYMENT_TIMEOUT_LABEL,
        size,
        elapsedMs,
        String.format("%.2f", (double) elapsedMs / size));
  }

  @Transactional
  public void expirePastReservations() {
    LocalDateTime startedBefore = LocalDateTime.now().minus(Schedule.CHECK_IN_GRACE);
    long expired = reservationRepository.expirePastConfirmedReservations(startedBefore);
    if (expired > 0) {
      log.info("[{}] 처리 완료 ({}건)", NO_SHOW_LABEL, expired);
    }
  }

  private static final class ExpiryRun {

    private final LocalDateTime deadline;
    private final ExpiryMode mode;
    private final int chunkSize;
    private final Set<Long> givenUp = ConcurrentHashMap.newKeySet();
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong maxTxNanos = new AtomicLong();

    private ExpiryRun(LocalDateTime deadline, ExpiryMode mode, int chunkSize) {
      this.deadline = deadline;
      this.mode = mode;
      this.chunkSize = chunkSize;
    }

    private int total() {
      return processed.get() + skipped.get() + failed.get();
    }
  }
}
