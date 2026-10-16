package com.popnup.popnupbackend.domain.schedule.service;

import static com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityKeys.maxKey;
import static com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityKeys.ttlUntilEndOfDate;
import static com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityKeys.usedKey;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleCapacityCounterStore {

  public record Counter(Long max, Long used) {}

  public record Restore(Long scheduleId, LocalDate scheduleDate, long max, long used) {}

  private final StringRedisTemplate redisTemplate;

  public List<Counter> readCounters(List<Long> scheduleIds) {
    try {
      List<String> keys = new ArrayList<>(scheduleIds.size() * 2);
      for (Long id : scheduleIds) {
        keys.add(maxKey(id));
        keys.add(usedKey(id));
      }
      List<String> values = redisTemplate.opsForValue().multiGet(keys);
      if (values == null || values.size() != keys.size()) {
        return null;
      }
      List<Counter> counters = new ArrayList<>(scheduleIds.size());
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
      log.warn("[ScheduleCapacityCounterStore] Redis 카운터 읽기 실패, 재동기화를 건너뜁니다.", e);
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
      log.warn("[ScheduleCapacityCounterStore] Redis 카운터 덮어쓰기 실패. count={}", restores.size(), e);
      return false;
    }
  }
}
