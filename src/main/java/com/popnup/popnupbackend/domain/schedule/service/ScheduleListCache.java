package com.popnup.popnupbackend.domain.schedule.service;

import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class ScheduleListCache {

  public enum Stampede {
    NONE,
    LOGICAL_EXPIRY
  }

  private static final String KEY_PREFIX = "schedule:list:";
  private static final String LOGICAL_PREFIX = "schedule:list:v2:";
  private static final String LOCK_PREFIX = "schedule:list:lock:";
  private static final long WAIT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

  private final StringRedisTemplate redisTemplate;
  private final JsonMapper jsonMapper;
  private final MeterRegistry meterRegistry;
  private final ObjectProvider<RedissonClient> redissonProvider;
  private final Duration ttl;
  private final Stampede stampede;
  private final long lockWaitMs;
  private final long lockLeaseMs;

  @Autowired
  public ScheduleListCache(
      StringRedisTemplate redisTemplate,
      JsonMapper jsonMapper,
      MeterRegistry meterRegistry,
      ObjectProvider<RedissonClient> redissonProvider,
      @Value("${schedule.list-cache.ttl-seconds:0}") long ttlSeconds,
      @Value("${schedule.list-cache.stampede:LOGICAL_EXPIRY}") Stampede stampede,
      @Value("${schedule.list-cache.lock-wait-ms:50}") long lockWaitMs,
      @Value("${schedule.list-cache.lock-lease-ms:1000}") long lockLeaseMs) {
    this.redisTemplate = redisTemplate;
    this.jsonMapper = jsonMapper;
    this.meterRegistry = meterRegistry;
    this.redissonProvider = redissonProvider;
    this.ttl = Duration.ofSeconds(Math.max(ttlSeconds, 0));
    this.stampede = stampede;
    this.lockWaitMs = lockWaitMs;
    this.lockLeaseMs = lockLeaseMs;
  }

  public ScheduleListCache(
      StringRedisTemplate redisTemplate,
      JsonMapper jsonMapper,
      MeterRegistry meterRegistry,
      long ttlSeconds) {
    this(redisTemplate, jsonMapper, meterRegistry, null, ttlSeconds, Stampede.NONE, 0, 0);
  }

  public record Snapshot(
      Long scheduleId,
      String popupTitle,
      LocalDate scheduleDate,
      LocalTime startTime,
      LocalTime endTime,
      int maxCapacity,
      int nowCapacity,
      boolean active) {

    static Snapshot from(Schedule s) {
      return new Snapshot(
          s.getId(),
          s.getPopup().getTitle(),
          s.getScheduleDate(),
          s.getStartTime(),
          s.getEndTime(),
          s.getMaxCapacity(),
          s.getNowCapacity(),
          s.isActive());
    }

    ScheduleResponse toResponse(LocalDateTime now) {
      return ScheduleResponse.of(
          scheduleId,
          popupTitle,
          scheduleDate,
          startTime,
          endTime,
          maxCapacity,
          nowCapacity,
          active,
          now);
    }
  }

  public record Entry(long refreshAt, List<Snapshot> items) {}

  public boolean enabled() {
    return !ttl.isZero();
  }

  public List<Snapshot> getOrLoad(Long popupId, LocalDate date, Supplier<List<Schedule>> loader) {
    long started = System.nanoTime();
    String path;
    List<Snapshot> result;
    switch (stampede) {
      case LOGICAL_EXPIRY -> {
        Optional<Entry> entry = getEntry(popupId, date);
        if (entry.isPresent() && entry.get().refreshAt() > System.currentTimeMillis()) {
          path = "hit";
          result = entry.get().items();
        } else if (entry.isPresent()) {
          RLock lock = lock(popupId, date);
          if (tryLockNow(lock)) {
            path = "refresh";
            try {
              result = loadAndPut(popupId, date, loader);
            } finally {
              unlock(lock);
            }
          } else {
            path = "stale";
            result = entry.get().items();
            meterRegistry
                .summary("schedule.list.stale.age.ms")
                .record(System.currentTimeMillis() - (entry.get().refreshAt() - ttl.toMillis()));
          }
        } else {
          LoadResult r = loadWithLockWait(popupId, date, loader);
          path = r.path();
          result = r.items();
        }
      }
      default -> {
        Optional<List<Snapshot>> cached = get(popupId, date);
        if (cached.isPresent()) {
          path = "hit";
          result = cached.get();
        } else {
          path = "db";
          result = loadAndPut(popupId, date, loader);
        }
      }
    }
    Timer.builder("schedule.list.lookup")
        .tag("path", path)
        .publishPercentileHistogram()
        .register(meterRegistry)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    return result;
  }

  private record LoadResult(String path, List<Snapshot> items) {}

  private LoadResult loadWithLockWait(
      Long popupId, LocalDate date, Supplier<List<Schedule>> loader) {
    RLock lock = lock(popupId, date);
    if (tryLockNow(lock)) {
      try {
        Optional<List<Snapshot>> again = read(popupId, date);
        if (again.isPresent()) {
          return new LoadResult("hit", again.get());
        }
        return new LoadResult("db", loadAndPut(popupId, date, loader));
      } finally {
        unlock(lock);
      }
    }
    long waitStarted = System.nanoTime();
    long deadline = waitStarted + TimeUnit.MILLISECONDS.toNanos(lockWaitMs);
    while (System.nanoTime() < deadline) {
      LockSupport.parkNanos(WAIT_POLL_NANOS);
      Optional<List<Snapshot>> filled = read(popupId, date);
      if (filled.isPresent()) {
        recordLockWait("filled", waitStarted);
        return new LoadResult("wait_hit", filled.get());
      }
    }
    recordLockWait("timeout", waitStarted);
    return new LoadResult("wait_db", loadAndPut(popupId, date, loader));
  }

  private void recordLockWait(String result, long waitStarted) {
    Timer.builder("schedule.list.lock.wait")
        .tag("result", result)
        .publishPercentileHistogram()
        .register(meterRegistry)
        .record(System.nanoTime() - waitStarted, TimeUnit.NANOSECONDS);
  }

  private Optional<List<Snapshot>> read(Long popupId, LocalDate date) {
    return getEntry(popupId, date).map(Entry::items);
  }

  private List<Snapshot> loadAndPut(Long popupId, LocalDate date, Supplier<List<Schedule>> loader) {
    meterRegistry.counter("schedule.list.db.load").increment();
    List<Snapshot> snapshots = loader.get().stream().map(Snapshot::from).toList();
    putSnapshots(popupId, date, snapshots);
    return snapshots;
  }

  private RLock lock(Long popupId, LocalDate date) {
    return redissonProvider.getObject().getLock(LOCK_PREFIX + popupId + ":" + date);
  }

  private boolean tryLockNow(RLock lock) {
    try {
      return lock.tryLock(0, lockLeaseMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      log.warn("[ScheduleListCache] 락 획득 실패, 락 없이 진행합니다: {}", e.toString());
      return true;
    }
  }

  private void unlock(RLock lock) {
    try {
      if (lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    } catch (Exception e) {
      log.debug("[ScheduleListCache] 락 해제 실패 (보유 시간 뒤 풀림): {}", e.toString());
    }
  }

  public Optional<List<Snapshot>> get(Long popupId, LocalDate date) {
    try {
      String json = redisTemplate.opsForValue().get(key(popupId, date));
      if (json == null) {
        count("miss");
        return Optional.empty();
      }
      count("hit");
      return Optional.of(jsonMapper.readValue(json, new TypeReference<List<Snapshot>>() {}));
    } catch (Exception e) {
      count("error");
      log.warn("[ScheduleListCache] 조회 실패, DB로 대체합니다. popupId={}, date={}", popupId, date, e);
      return Optional.empty();
    }
  }

  public Optional<Entry> getEntry(Long popupId, LocalDate date) {
    try {
      String json = redisTemplate.opsForValue().get(logicalKey(popupId, date));
      if (json == null) {
        count("miss");
        return Optional.empty();
      }
      Entry entry = jsonMapper.readValue(json, Entry.class);
      count(entry.refreshAt() > System.currentTimeMillis() ? "hit" : "stale");
      return Optional.of(entry);
    } catch (Exception e) {
      count("error");
      log.warn("[ScheduleListCache] 조회 실패, DB로 대체합니다. popupId={}, date={}", popupId, date, e);
      return Optional.empty();
    }
  }

  public void put(Long popupId, LocalDate date, List<Schedule> schedules) {
    putSnapshots(popupId, date, schedules.stream().map(Snapshot::from).toList());
  }

  private void putSnapshots(Long popupId, LocalDate date, List<Snapshot> snapshots) {
    try {
      if (stampede == Stampede.LOGICAL_EXPIRY) {
        Entry entry = new Entry(System.currentTimeMillis() + ttl.toMillis(), snapshots);
        redisTemplate
            .opsForValue()
            .set(
                logicalKey(popupId, date),
                jsonMapper.writeValueAsString(entry),
                ttl.multipliedBy(2));
      } else {
        redisTemplate
            .opsForValue()
            .set(key(popupId, date), jsonMapper.writeValueAsString(snapshots), ttl);
      }
    } catch (Exception e) {
      log.warn("[ScheduleListCache] 저장 실패 popupId={}, date={}", popupId, date, e);
    }
  }

  public void evictAfterCommit(Long popupId, LocalDate date) {
    if (!enabled()) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              evict(popupId, date);
            }
          });
    } else {
      evict(popupId, date);
    }
  }

  private void evict(Long popupId, LocalDate date) {
    try {
      redisTemplate.delete(List.of(key(popupId, date), logicalKey(popupId, date)));
    } catch (Exception e) {
      log.warn("[ScheduleListCache] 삭제 실패 popupId={}, date={}", popupId, date, e);
    }
  }

  private void count(String result) {
    meterRegistry.counter("schedule.list.cache", "result", result).increment();
  }

  private static String key(Long popupId, LocalDate date) {
    return KEY_PREFIX + popupId + ":" + date;
  }

  private static String logicalKey(Long popupId, LocalDate date) {
    return LOGICAL_PREFIX + popupId + ":" + date;
  }
}
