package com.popnup.popnupbackend.domain.reservation.perf;

import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor;
import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor.ExpiryMode;
import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor.ExpiryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("loadtest")
@Endpoint(id = "perfrace")
@RequiredArgsConstructor
public class ConfirmExpireRaceEndpoint {

  private final ReservationService reservationService;
  private final ReservationTimeoutProcessor reservationTimeoutProcessor;

  @WriteOperation
  public Map<String, Object> race(long fromId, long toId, int threads) throws Exception {
    int workers = Math.max(1, threads);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(workers + 1);
    AtomicInteger confirmed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    ConcurrentLinkedQueue<Long> confirmNanos = new ConcurrentLinkedQueue<>();
    try {
      Future<ExpiryResult> expiry =
          pool.submit(
              () -> {
                start.await();
                return reservationTimeoutProcessor.expireUnpaidReservations(
                    ExpiryMode.CHUNK, 100, 1);
              });
      List<Future<?>> confirms = new ArrayList<>();
      for (int w = 0; w < workers; w++) {
        int offset = w;
        confirms.add(
            pool.submit(
                () -> {
                  start.await();
                  for (long id = fromId + offset; id <= toId; id += workers) {
                    long t0 = System.nanoTime();
                    try {
                      reservationService.confirmReservation(id);
                      confirmed.incrementAndGet();
                    } catch (Exception e) {
                      rejected.incrementAndGet();
                    } finally {
                      confirmNanos.add(System.nanoTime() - t0);
                    }
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> f : confirms) {
        f.get();
      }
      ExpiryResult result = expiry.get();
      return Map.of(
          "confirmed",
          confirmed.get(),
          "confirmRejected",
          rejected.get(),
          "confirmP99Ms",
          p99Ms(confirmNanos),
          "expiry",
          result);
    } finally {
      pool.shutdown();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private static double p99Ms(ConcurrentLinkedQueue<Long> nanos) {
    List<Long> sorted = new ArrayList<>(nanos);
    if (sorted.isEmpty()) {
      return 0;
    }
    sorted.sort(null);
    int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.99) - 1);
    return sorted.get(Math.max(0, index)) / 1_000_000.0;
  }
}
