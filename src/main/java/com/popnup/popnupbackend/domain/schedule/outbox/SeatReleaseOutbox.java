package com.popnup.popnupbackend.domain.schedule.outbox;

import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class SeatReleaseOutbox {

  private final boolean enabled;
  private final boolean fastPath;
  private final Executor fastPathExecutor;
  private final OutboxEventRepository outboxEventRepository;
  private final ScheduleCapacityCache scheduleCapacityCache;

  @Autowired
  public SeatReleaseOutbox(
      @Value("${outbox.enabled:true}") boolean enabled,
      @Value("${outbox.mode:polling}") String mode,
      @Value("${outbox.fast-path.enabled:true}") boolean fastPathEnabled,
      @Value("${outbox.fast-path.threads:4}") int fastPathThreads,
      OutboxEventRepository outboxEventRepository,
      ScheduleCapacityCache scheduleCapacityCache) {
    this(
        enabled,
        enabled && fastPathEnabled && "polling".equals(mode),
        newFastPathExecutor(fastPathThreads),
        outboxEventRepository,
        scheduleCapacityCache);
  }

  SeatReleaseOutbox(
      boolean enabled,
      boolean fastPath,
      Executor fastPathExecutor,
      OutboxEventRepository outboxEventRepository,
      ScheduleCapacityCache scheduleCapacityCache) {
    this.enabled = enabled;
    this.fastPath = fastPath;
    this.fastPathExecutor = fastPathExecutor;
    this.outboxEventRepository = outboxEventRepository;
    this.scheduleCapacityCache = scheduleCapacityCache;
  }

  private static ExecutorService newFastPathExecutor(int threads) {
    AtomicInteger seq = new AtomicInteger();
    return new ThreadPoolExecutor(
        Math.max(1, threads),
        Math.max(1, threads),
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(10_000),
        r -> {
          Thread t = new Thread(r, "outbox-fast-" + seq.incrementAndGet());
          t.setDaemon(true);
          return t;
        },
        new ThreadPoolExecutor.DiscardPolicy());
  }

  @PreDestroy
  void shutdown() {
    if (fastPathExecutor instanceof ExecutorService service) {
      service.shutdown();
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Map<Long, Integer> seatsBySchedule) {
    if (seatsBySchedule.isEmpty()) {
      return;
    }
    if (!enabled) {
      seatsBySchedule.forEach(scheduleCapacityCache::releaseAfterCommit);
      return;
    }
    OutboxEvent event =
        outboxEventRepository.save(
            OutboxEvent.of(OutboxEvent.SEAT_RELEASE, encode(seatsBySchedule), LocalDateTime.now()));
    if (fastPath && TransactionSynchronizationManager.isSynchronizationActive()) {
      Long eventId = event.getId();
      Map<Long, Integer> seats = Map.copyOf(seatsBySchedule);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              fastPathExecutor.execute(() -> applyOnce(eventId, seats));
            }
          });
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Long scheduleId, int count) {
    record(Map.of(scheduleId, count));
  }

  private void applyOnce(Long eventId, Map<Long, Integer> seats) {
    try {
      scheduleCapacityCache.applySeatRelease(eventId, seats);
    } catch (Exception e) {
      log.debug("[SeatReleaseOutbox] 커밋 직후 반영 실패, 중계기가 다시 반영합니다. eventId={}", eventId, e);
    }
  }

  public boolean apply(OutboxEvent event) {
    return scheduleCapacityCache.applySeatRelease(event.getId(), decode(event.getPayload()));
  }

  static String encode(Map<Long, Integer> seatsBySchedule) {
    return new TreeMap<>(seatsBySchedule)
        .entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .collect(Collectors.joining(","));
  }

  static Map<Long, Integer> decode(String payload) {
    Map<Long, Integer> seats = new LinkedHashMap<>();
    for (String pair : payload.split(",")) {
      String[] p = pair.split(":");
      seats.merge(Long.valueOf(p[0]), Integer.valueOf(p[1]), Integer::sum);
    }
    return seats;
  }
}
