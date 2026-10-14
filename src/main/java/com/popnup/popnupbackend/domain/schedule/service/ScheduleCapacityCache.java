package com.popnup.popnupbackend.domain.schedule.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleCapacityCache {

  private static final String USED_KEY_PREFIX = "schedule:capacity:used:";
  private static final String MAX_KEY_PREFIX = "schedule:capacity:max:";
  private static final int RELEASE_MAX_ATTEMPTS = 3;

  private final StringRedisTemplate redisTemplate;

  private static final RedisScript<Long> TRY_RESERVE_SCRIPT =
      new DefaultRedisScript<>(
          "local max = redis.call('GET', KEYS[1]) "
              + "if max == false then return -1 end "
              + "local newUsed = redis.call('INCRBY', KEYS[2], ARGV[1]) "
              + "if newUsed > tonumber(max) then "
              + "  redis.call('DECRBY', KEYS[2], ARGV[1]) "
              + "  return 0 "
              + "end "
              + "return 1",
          Long.class);

  private static final RedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          "local current = tonumber(redis.call('GET', KEYS[1]) or '0') "
              + "local newVal = current - tonumber(ARGV[1]) "
              + "if newVal < 0 then newVal = 0 end "
              + "redis.call('SET', KEYS[1], newVal) "
              + "return newVal",
          Long.class);

  public void init(Long scheduleId, LocalDate scheduleDate, int maxCapacity) {
    try {
      Duration ttl = ttlUntilEndOfDate(scheduleDate);
      redisTemplate.opsForValue().set(maxKey(scheduleId), String.valueOf(maxCapacity), ttl);
      redisTemplate.opsForValue().set(usedKey(scheduleId), "0", ttl);
    } catch (Exception e) {
      log.warn("[ScheduleCapacityCache] Redis 초기화 실패, DB만으로 동작합니다. scheduleId={}", scheduleId, e);
    }
  }

  public record Counter(Long max, Long used) {}

  public record Restore(Long scheduleId, LocalDate scheduleDate, long max, long used) {}

  public List<Counter> readCounters(List<Long> scheduleIds) {
    try {
      List<String> keys = new java.util.ArrayList<>(scheduleIds.size() * 2);
      for (Long id : scheduleIds) {
        keys.add(maxKey(id));
        keys.add(usedKey(id));
      }
      List<String> values = redisTemplate.opsForValue().multiGet(keys);
      if (values == null || values.size() != keys.size()) {
        return null;
      }
      List<Counter> counters = new java.util.ArrayList<>(scheduleIds.size());
      for (int i = 0; i < scheduleIds.size(); i++) {
        String max = values.get(i * 2);
        String used = values.get(i * 2 + 1);
        counters.add(
            new Counter(
                max == null ? null : Long.parseLong(max),
                used == null ? null : Long.parseLong(used)));
      }
      return counters;
    } catch (Exception e) {
      log.warn("[ScheduleCapacityCache] Redis 카운터 읽기 실패, 재동기화를 건너뜁니다.", e);
      return null;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public boolean restoreAll(List<Restore> restores) {
    if (restores.isEmpty()) {
      return true;
    }
    try {
      redisTemplate.executePipelined(
          new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
              ValueOperations<String, String> ops = operations.opsForValue();
              for (Restore r : restores) {
                Duration ttl = ttlUntilEndOfDate(r.scheduleDate());
                ops.set(maxKey(r.scheduleId()), String.valueOf(r.max()), ttl);
                ops.set(usedKey(r.scheduleId()), String.valueOf(r.used()), ttl);
              }
              return null;
            }
          });
      return true;
    } catch (Exception e) {
      log.warn("[ScheduleCapacityCache] Redis 카운터 덮어쓰기 실패. count={}", restores.size(), e);
      return false;
    }
  }

  public boolean isSoldOut(Long scheduleId, int count) {
    try {
      List<String> values =
          redisTemplate.opsForValue().multiGet(List.of(maxKey(scheduleId), usedKey(scheduleId)));
      if (values == null || values.size() < 2 || values.get(0) == null || values.get(1) == null) {
        return false;
      }
      return Long.parseLong(values.get(1)) + count > Long.parseLong(values.get(0));
    } catch (Exception e) {
      log.warn("[ScheduleCapacityCache] Redis 선확인 실패, 선확인을 건너뜁니다. scheduleId={}", scheduleId, e);
      return false;
    }
  }

  public boolean tryReserve(Long scheduleId, int count) {
    try {
      List<String> keys = List.of(maxKey(scheduleId), usedKey(scheduleId));
      Long result = redisTemplate.execute(TRY_RESERVE_SCRIPT, keys, String.valueOf(count));

      if (result == null || result == -1L) {
        return true;
      }
      return result == 1L;
    } catch (Exception e) {
      log.warn("[ScheduleCapacityCache] Redis 조회/증가 실패, 필터링을 건너뜁니다. scheduleId={}", scheduleId, e);
      return true;
    }
  }

  public void release(Long scheduleId, int count) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              doRelease(scheduleId, count);
            }
          });
    } else {
      doRelease(scheduleId, count);
    }
  }

  public void compensate(Long scheduleId, int count) {
    doRelease(scheduleId, count);
  }

  private void doRelease(Long scheduleId, int count) {
    List<String> keys = Collections.singletonList(usedKey(scheduleId));

    for (int attempt = 1; attempt <= RELEASE_MAX_ATTEMPTS; attempt++) {
      try {
        redisTemplate.execute(RELEASE_SCRIPT, keys, String.valueOf(count));
        return;
      } catch (Exception e) {
        log.warn(
            "[ScheduleCapacityCache] Redis 카운터 복구 실패 (시도 {}/{}). scheduleId={}",
            attempt,
            RELEASE_MAX_ATTEMPTS,
            scheduleId,
            e);
        if (attempt == RELEASE_MAX_ATTEMPTS) {
          log.error(
              "[ScheduleCapacityCache] Redis 카운터 복구 최종 실패 - 드리프트 발생 가능. "
                  + "재동기화 배치에서 보정 필요. scheduleId={}, count={}",
              scheduleId,
              count);
        }
      }
    }
  }

  private Duration ttlUntilEndOfDate(LocalDate scheduleDate) {
    LocalDateTime endOfDay = LocalDateTime.of(scheduleDate, LocalTime.MAX);
    Duration ttl = Duration.between(LocalDateTime.now(), endOfDay);
    return ttl.isNegative() ? Duration.ofMinutes(1) : ttl.plusHours(1);
  }

  private String usedKey(Long scheduleId) {
    return USED_KEY_PREFIX + scheduleId;
  }

  private String maxKey(Long scheduleId) {
    return MAX_KEY_PREFIX + scheduleId;
  }
}
