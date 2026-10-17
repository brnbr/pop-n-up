package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleListCache.Snapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class ScheduleListCacheTest {

  private static final LocalDate DAY = LocalDate.of(2031, 5, 10);
  private static final String KEY = "schedule:list:1:2031-05-10";

  private ValueOperations<String, String> valueOps;
  private SimpleMeterRegistry meterRegistry;
  private ScheduleListCache cache;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    given(redisTemplate.opsForValue()).willReturn(valueOps);
    meterRegistry = new SimpleMeterRegistry();
    cache = new ScheduleListCache(redisTemplate, JsonMapper.builder().build(), meterRegistry, 30);
  }

  private Schedule schedule(long id, int startHour) {
    Popup popup = mock(Popup.class);
    given(popup.getTitle()).willReturn("테스트 팝업");
    Schedule schedule = mock(Schedule.class);
    given(schedule.getId()).willReturn(id);
    given(schedule.getPopup()).willReturn(popup);
    given(schedule.getScheduleDate()).willReturn(DAY);
    given(schedule.getStartTime()).willReturn(LocalTime.of(startHour, 0));
    given(schedule.getEndTime()).willReturn(LocalTime.of(startHour + 1, 0));
    given(schedule.getMaxCapacity()).willReturn(30);
    given(schedule.getNowCapacity()).willReturn(7);
    given(schedule.isActive()).willReturn(true);
    return schedule;
  }

  private double count(String result) {
    return meterRegistry.counter("schedule.list.cache", "result", result).count();
  }

  @Test
  @DisplayName("저장한 일정 목록을 그대로 다시 읽는다 (날짜·시각 포함), 키와 TTL이 맞다")
  void putThenGet_roundTrip() {
    cache.put(1L, DAY, List.of(schedule(100L, 10), schedule(101L, 11)));

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(valueOps).set(eq(KEY), json.capture(), eq(Duration.ofSeconds(30)));
    given(valueOps.get(KEY)).willReturn(json.getValue());

    Optional<List<Snapshot>> result = cache.get(1L, DAY);

    assertThat(result).isPresent();
    assertThat(result.get())
        .extracting(Snapshot::scheduleId, Snapshot::scheduleDate, Snapshot::startTime)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(100L, DAY, LocalTime.of(10, 0)),
            org.assertj.core.groups.Tuple.tuple(101L, DAY, LocalTime.of(11, 0)));
    assertThat(result.get().get(0).popupTitle()).isEqualTo("테스트 팝업");
    assertThat(result.get().get(0).maxCapacity()).isEqualTo(30);
    assertThat(result.get().get(0).nowCapacity()).isEqualTo(7);
    assertThat(result.get().get(0).active()).isTrue();
    assertThat(count("hit")).isEqualTo(1);
  }

  @Test
  @DisplayName("저장된 값이 깨져 있으면 예외 없이 빈 결과(DB로 대체)이고 error로 센다")
  void get_corruptedJson_fallsBack() {
    given(valueOps.get(anyString())).willReturn("{not json");

    assertThat(cache.get(1L, DAY)).isEmpty();
    assertThat(count("error")).isEqualTo(1);
  }
}
