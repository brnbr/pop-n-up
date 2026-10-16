package com.popnup.popnupbackend.domain.schedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class ScheduleListCache {

  private static final String KEY_PREFIX = "schedule:list:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final Duration ttl;

  public ScheduleListCache(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${schedule.list-cache.ttl-seconds:0}") long ttlSeconds) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.ttl = Duration.ofSeconds(Math.max(ttlSeconds, 0));
  }

  public record Snapshot(
      Long scheduleId,
      String popupTitle,
      String scheduleDate,
      String startTime,
      String endTime,
      int maxCapacity,
      int nowCapacity,
      boolean active) {

    static Snapshot from(Schedule s) {
      return new Snapshot(
          s.getId(),
          s.getPopup().getTitle(),
          s.getScheduleDate().toString(),
          s.getStartTime().toString(),
          s.getEndTime().toString(),
          s.getMaxCapacity(),
          s.getNowCapacity(),
          s.isActive());
    }

    ScheduleResponse toResponse(LocalDateTime now) {
      return ScheduleResponse.of(
          scheduleId,
          popupTitle,
          LocalDate.parse(scheduleDate),
          LocalTime.parse(startTime),
          LocalTime.parse(endTime),
          maxCapacity,
          nowCapacity,
          active,
          now);
    }
  }

  public boolean enabled() {
    return !ttl.isZero();
  }

  public Optional<List<Snapshot>> get(Long popupId, LocalDate date) {
    try {
      String json = redisTemplate.opsForValue().get(key(popupId, date));
      if (json == null) {
        count("miss");
        return Optional.empty();
      }
      count("hit");
      return Optional.of(objectMapper.readValue(json, new TypeReference<List<Snapshot>>() {}));
    } catch (Exception e) {
      count("error");
      log.warn("[ScheduleListCache] 조회 실패, DB로 대체합니다. popupId={}, date={}", popupId, date, e);
      return Optional.empty();
    }
  }

  public void put(Long popupId, LocalDate date, List<Schedule> schedules) {
    try {
      List<Snapshot> snapshots = schedules.stream().map(Snapshot::from).toList();
      redisTemplate
          .opsForValue()
          .set(key(popupId, date), objectMapper.writeValueAsString(snapshots), ttl);
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
      redisTemplate.delete(key(popupId, date));
    } catch (Exception e) {
      log.warn(
          "[ScheduleListCache] 삭제 실패, TTL 만료까지 이전 값이 보일 수 있습니다. popupId={}, date={}",
          popupId,
          date,
          e);
    }
  }

  private void count(String result) {
    meterRegistry.counter("schedule.list.cache", "result", result).increment();
  }

  private static String key(Long popupId, LocalDate date) {
    return KEY_PREFIX + popupId + ":" + date;
  }
}
