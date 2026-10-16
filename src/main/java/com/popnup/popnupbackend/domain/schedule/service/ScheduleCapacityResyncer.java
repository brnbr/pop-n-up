package com.popnup.popnupbackend.domain.schedule.service;

import com.popnup.popnupbackend.domain.schedule.repository.ScheduleCapacitySnapshot;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Counter;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Restore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
  private final AtomicLong lastMaxAbsDrift = new AtomicLong();

  public ScheduleCapacityResyncer(
      ScheduleRepository scheduleRepository,
      ScheduleCapacityCounterStore counterStore,
      MeterRegistry meterRegistry) {
    this.scheduleRepository = scheduleRepository;
    this.counterStore = counterStore;
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("schedule.capacity.resync.max.abs.drift", lastMaxAbsDrift);
  }

  private record Drift(Long redisMax, Long redisUsed, int dbMax, int dbUsed) {}

  public record Result(
      int scanned,
      int initialized,
      int corrected,
      int pendingDrift,
      long maxAbsDrift,
      long durationMs) {}

  private static class Tally {
    int scanned;
    int initialized;
    int corrected;
    int pending;
    long maxAbsDrift;
  }

  private static class ChunkPlan {
    final List<Restore> restores = new ArrayList<>();
    int initialized;
    int corrected;
    int pending;
    long maxAbsDrift;
  }

  public Result resync() {
    long startNanos = System.nanoTime();
    LocalDate today = LocalDate.now();
    Tally tally = new Tally();
    Set<Long> seen = new HashSet<>();

    boolean redisAvailable = resyncChunks(today, tally, seen);
    if (redisAvailable) {
      pendingDrifts.keySet().retainAll(seen);
    }

    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
    record(tally, durationMs);
    logResult(tally, durationMs);
    return new Result(
        tally.scanned,
        tally.initialized,
        tally.corrected,
        tally.pending,
        tally.maxAbsDrift,
        durationMs);
  }

  private boolean resyncChunks(LocalDate today, Tally tally, Set<Long> seen) {
    Long afterId = 0L;
    while (true) {
      List<ScheduleCapacitySnapshot> chunk =
          scheduleRepository.findCapacitySnapshots(today, afterId, PageRequest.of(0, CHUNK_SIZE));
      if (chunk.isEmpty()) {
        return true;
      }
      afterId = chunk.get(chunk.size() - 1).id();
      tally.scanned += chunk.size();

      List<Long> ids = chunk.stream().map(ScheduleCapacitySnapshot::id).toList();
      seen.addAll(ids);

      List<Counter> counters = counterStore.readCounters(ids);
      if (counters == null) {
        return false;
      }

      ChunkPlan plan = planChunk(chunk, counters);
      if (counterStore.restoreAll(plan.restores)) {
        tally.initialized += plan.initialized;
        tally.corrected += plan.corrected;
      }
      tally.pending += plan.pending;
      tally.maxAbsDrift = Math.max(tally.maxAbsDrift, plan.maxAbsDrift);
    }
  }

  private ChunkPlan planChunk(List<ScheduleCapacitySnapshot> chunk, List<Counter> counters) {
    ChunkPlan plan = new ChunkPlan();
    for (int i = 0; i < chunk.size(); i++) {
      classify(chunk.get(i), counters.get(i), plan);
    }
    return plan;
  }

  private void classify(ScheduleCapacitySnapshot snap, Counter counter, ChunkPlan plan) {
    Long id = snap.id();

    if (counter.max() == null || counter.used() == null) {
      plan.restores.add(restoreOf(snap));
      pendingDrifts.remove(id);
      plan.initialized++;
      return;
    }

    long drift = counter.used() - snap.nowCapacity();
    boolean maxMismatch = counter.max() != snap.maxCapacity().longValue();
    if (drift == 0 && !maxMismatch) {
      pendingDrifts.remove(id);
      return;
    }

    plan.maxAbsDrift = Math.max(plan.maxAbsDrift, Math.abs(drift));
    Drift observed =
        new Drift(counter.max(), counter.used(), snap.maxCapacity(), snap.nowCapacity());
    if (observed.equals(pendingDrifts.get(id))) {
      plan.restores.add(restoreOf(snap));
      pendingDrifts.remove(id);
      plan.corrected++;
    } else {
      pendingDrifts.put(id, observed);
      plan.pending++;
    }
  }

  private Restore restoreOf(ScheduleCapacitySnapshot snap) {
    return new Restore(snap.id(), snap.scheduleDate(), snap.maxCapacity(), snap.nowCapacity());
  }

  private void record(Tally tally, long durationMs) {
    meterRegistry.timer("schedule.capacity.resync.duration").record(Duration.ofMillis(durationMs));
    meterRegistry
        .counter("schedule.capacity.resync", "result", "initialized")
        .increment(tally.initialized);
    meterRegistry
        .counter("schedule.capacity.resync", "result", "corrected")
        .increment(tally.corrected);
    meterRegistry.counter("schedule.capacity.resync", "result", "pending").increment(tally.pending);
    lastMaxAbsDrift.set(tally.maxAbsDrift);
  }

  private void logResult(Tally tally, long durationMs) {
    if (tally.initialized > 0 || tally.corrected > 0) {
      log.info(
          "[CapacityResync] 스캔 {}건, 초기화 {}건, 보정 {}건, 확인 대기 {}건, 최대 어긋남 {}, 소요 {}ms",
          tally.scanned,
          tally.initialized,
          tally.corrected,
          tally.pending,
          tally.maxAbsDrift,
          durationMs);
    } else {
      log.debug("[CapacityResync] 스캔 {}건, 변경 없음, 소요 {}ms", tally.scanned, durationMs);
    }
  }
}
