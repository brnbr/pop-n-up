package com.popnup.popnupbackend.domain.schedule.service;

import com.popnup.popnupbackend.domain.schedule.repository.ScheduleCapacitySnapshot;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Counter;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Restore;
import com.popnup.popnupbackend.domain.schedule.support.KstTime;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ScheduleCapacityResyncer {

  private static final int CHUNK_SIZE = 500;

  private final ScheduleRepository scheduleRepository;
  private final ScheduleCapacityCounterStore counterStore;
  private final MeterRegistry meterRegistry;

  private final Map<Long, Drift> pendingDrifts = new ConcurrentHashMap<>();

  public ScheduleCapacityResyncer(
      ScheduleRepository scheduleRepository,
      ScheduleCapacityCounterStore counterStore,
      MeterRegistry meterRegistry) {
    this.scheduleRepository = scheduleRepository;
    this.counterStore = counterStore;
    this.meterRegistry = meterRegistry;
  }

  private record Drift(Long redisMax, Long redisUsed, int dbMax, int dbUsed) {}

  public record Result(
      int scanned,
      int initialized,
      int corrected,
      int pendingDrift,
      long maxAbsDrift,
      long durationMs) {}

  public Result resync() {
    long startNanos = System.nanoTime();
    LocalDate today = KstTime.now().toLocalDate();
    Long afterId = 0L;
    int scanned = 0;
    int initialized = 0;
    int corrected = 0;
    int pending = 0;
    long maxAbsDrift = 0;
    Set<Long> seen = new HashSet<>();
    boolean redisUnavailable = false;

    while (true) {
      List<ScheduleCapacitySnapshot> chunk =
          scheduleRepository.findCapacitySnapshots(today, afterId, PageRequest.of(0, CHUNK_SIZE));
      if (chunk.isEmpty()) {
        break;
      }
      afterId = chunk.get(chunk.size() - 1).id();
      scanned += chunk.size();

      List<Long> ids = new ArrayList<>(chunk.size());
      for (ScheduleCapacitySnapshot snap : chunk) {
        ids.add(snap.id());
        seen.add(snap.id());
      }

      List<Counter> counters = counterStore.readCounters(ids);
      if (counters == null) {
        redisUnavailable = true;
        break;
      }

      List<Restore> restores = new ArrayList<>();
      int chunkInitialized = 0;
      int chunkCorrected = 0;
      for (int i = 0; i < chunk.size(); i++) {
        ScheduleCapacitySnapshot snap = chunk.get(i);
        Counter counter = counters.get(i);
        Restore restore =
            new Restore(snap.id(), snap.scheduleDate(), snap.maxCapacity(), snap.nowCapacity());

        if (counter.max() == null || counter.used() == null) {
          restores.add(restore);
          pendingDrifts.remove(snap.id());
          chunkInitialized++;
          continue;
        }

        long drift = counter.used() - snap.nowCapacity();
        boolean maxMismatch = counter.max() != snap.maxCapacity().longValue();
        if (drift == 0 && !maxMismatch) {
          pendingDrifts.remove(snap.id());
          continue;
        }

        maxAbsDrift = Math.max(maxAbsDrift, Math.abs(drift));
        Drift observed =
            new Drift(counter.max(), counter.used(), snap.maxCapacity(), snap.nowCapacity());
        if (observed.equals(pendingDrifts.get(snap.id()))) {
          restores.add(restore);
          pendingDrifts.remove(snap.id());
          chunkCorrected++;
        } else {
          pendingDrifts.put(snap.id(), observed);
          pending++;
        }
      }

      if (counterStore.restoreAll(restores)) {
        initialized += chunkInitialized;
        corrected += chunkCorrected;
      }
    }

    if (!redisUnavailable) {
      pendingDrifts.keySet().retainAll(seen);
    }

    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
    record(initialized, corrected, pending, maxAbsDrift, durationMs);
    if (initialized > 0 || corrected > 0) {
      log.info(
          "[CapacityResync] 스캔 {}건, 초기화 {}건, 보정 {}건, 확인 대기 {}건, 최대 어긋남 {}, 소요 {}ms",
          scanned,
          initialized,
          corrected,
          pending,
          maxAbsDrift,
          durationMs);
    } else {
      log.debug("[CapacityResync] 스캔 {}건, 변경 없음, 소요 {}ms", scanned, durationMs);
    }
    return new Result(scanned, initialized, corrected, pending, maxAbsDrift, durationMs);
  }

  private void record(
      int initialized, int corrected, int pending, long maxAbsDrift, long durationMs) {
    meterRegistry
        .timer("schedule.capacity.resync.duration")
        .record(java.time.Duration.ofMillis(durationMs));
    meterRegistry
        .counter("schedule.capacity.resync", "result", "initialized")
        .increment(initialized);
    meterRegistry.counter("schedule.capacity.resync", "result", "corrected").increment(corrected);
    meterRegistry.counter("schedule.capacity.resync", "result", "pending").increment(pending);
    meterRegistry.gauge("schedule.capacity.resync.max.abs.drift", maxAbsDrift);
  }
}
