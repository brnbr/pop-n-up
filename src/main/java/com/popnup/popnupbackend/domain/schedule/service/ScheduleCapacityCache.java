package com.popnup.popnupbackend.domain.schedule.service;

import static com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityKeys.inProgressKey;
import static com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityKeys.maxKey;
import static com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityKeys.usedKey;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Restore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleCapacityCache {

  private static final int RELEASE_MAX_ATTEMPTS = 3;
  private static final long TRY_RESERVE_KEY_MISSING = -1L;
  private static final long TRY_RESERVE_SUCCESS = 1L;
  private static final long TRY_RESERVE_IN_PROGRESS = 2L;

  private static final RedisScript<Long> TRY_RESERVE_SCRIPT =
      loadScript("redis/schedule-capacity-try-reserve.lua");
  private static final RedisScript<Long> RELEASE_SCRIPT =
      loadScript("redis/schedule-capacity-release.lua");
  private static final RedisScript<Long> RELEASE_EVENT_SCRIPT =
      loadScript("redis/schedule-capacity-release-event.lua");
  private static final String APPLIED_EVENT_KEY_PREFIX = "outbox:applied:";
  public static final String APPLIED_EVENT_COUNT_KEY = "outbox:applied:count";
  private static final String APPLIED_EVENT_TTL_SECONDS = String.valueOf(24 * 60 * 60);

  public enum ReserveResult {
    RESERVED,
    SOLD_OUT,
    IN_PROGRESS
  }

  private final StringRedisTemplate redisTemplate;
  private final ScheduleCapacityCounterStore counterStore;

  @Value("${reservation.booking.in-progress-ttl-ms:10000}")
  private long inProgressTtlMs;

  private static RedisScript<Long> loadScript(String path) {
    RedisScript<Long> script = RedisScript.of(new ClassPathResource(path), Long.class);
    script.getScriptAsString();
    return script;
  }

  public void initAfterCommit(List<Schedule> schedules) {
    if (schedules.isEmpty()) {
      return;
    }
    List<Restore> restores =
        schedules.stream()
            .map(s -> new Restore(s.getId(), s.getScheduleDate(), s.getMaxCapacity(), 0))
            .toList();
    runAfterCommit(() -> initAll(restores));
  }

  private void initAll(List<Restore> restores) {
    if (!counterStore.restoreAll(restores)) {
      log.warn(
          "[ScheduleCapacityCache] Redis 초기화 실패, DB만으로 동작합니다. 재동기화가 채웁니다. count={}",
          restores.size());
    }
  }

  public static List<Object> capacityKeys(Long scheduleId) {
    return List.of(maxKey(scheduleId), usedKey(scheduleId));
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

  public ReserveResult tryReserve(Long scheduleId, Long memberId, int count) {
    try {
      List<String> keys =
          List.of(maxKey(scheduleId), usedKey(scheduleId), inProgressKey(scheduleId, memberId));
      Long result =
          redisTemplate.execute(
              TRY_RESERVE_SCRIPT, keys, String.valueOf(count), String.valueOf(inProgressTtlMs));

      if (result == null || result == TRY_RESERVE_KEY_MISSING || result == TRY_RESERVE_SUCCESS) {
        return ReserveResult.RESERVED;
      }
      if (result == TRY_RESERVE_IN_PROGRESS) {
        return ReserveResult.IN_PROGRESS;
      }
      return ReserveResult.SOLD_OUT;
    } catch (Exception e) {
      log.warn("[ScheduleCapacityCache] Redis 조회/증가 실패, 필터링을 건너뜁니다. scheduleId={}", scheduleId, e);
      return ReserveResult.RESERVED;
    }
  }

  public boolean applySeatRelease(Long eventId, Map<Long, Integer> seatsBySchedule) {
    List<String> keys = new ArrayList<>(seatsBySchedule.size() + 2);
    List<String> args = new ArrayList<>(seatsBySchedule.size() + 2);
    keys.add(APPLIED_EVENT_KEY_PREFIX + eventId);
    keys.add(APPLIED_EVENT_COUNT_KEY);
    args.add(APPLIED_EVENT_TTL_SECONDS);
    args.add("");
    seatsBySchedule.forEach(
        (scheduleId, count) -> {
          keys.add(usedKey(scheduleId));
          args.add(String.valueOf(count));
        });
    Long result = redisTemplate.execute(RELEASE_EVENT_SCRIPT, keys, args.toArray());
    return result != null && result == 1L;
  }

  public void releaseAfterCommit(Long scheduleId, int count) {
    runAfterCommit(() -> releaseNow(scheduleId, count));
  }

  private void runAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  public void releaseNow(Long scheduleId, int count) {
    release(scheduleId, Collections.singletonList(usedKey(scheduleId)), count);
  }

  public void releaseNow(Long scheduleId, Long memberId, int count) {
    release(scheduleId, List.of(usedKey(scheduleId), inProgressKey(scheduleId, memberId)), count);
  }

  private void release(Long scheduleId, List<String> keys, int count) {
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
}
