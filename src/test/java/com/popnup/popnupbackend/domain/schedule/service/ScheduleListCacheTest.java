package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
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

  @Nested
  @DisplayName("만료 순간 동시 재조회 제어 [getOrLoad]")
  class StampedeTest {

    private static final String LOGICAL_KEY = "schedule:list:v2:1:2031-05-10";

    private StringRedisTemplate redisTemplate;
    private RLock lock;
    private AtomicInteger loads;
    private Supplier<List<Schedule>> loader;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUpStampede() {
      redisTemplate = mock(StringRedisTemplate.class);
      given(redisTemplate.opsForValue()).willReturn(valueOps);
      lock = mock(RLock.class);
      loads = new AtomicInteger();
      loader =
          () -> {
            loads.incrementAndGet();
            return List.of(schedule(100L, 10));
          };
    }

    @SuppressWarnings("unchecked")
    private ScheduleListCache cacheWith(ScheduleListCache.Stampede mode, long waitMs) {
      RedissonClient redisson = mock(RedissonClient.class);
      given(redisson.getLock(anyString())).willReturn(lock);
      ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
      given(provider.getObject()).willReturn(redisson);
      return new ScheduleListCache(
          redisTemplate,
          JsonMapper.builder().build(),
          meterRegistry,
          provider,
          3,
          mode,
          waitMs,
          30);
    }

    private String entryJson(long refreshAt) {
      return JsonMapper.builder()
          .build()
          .writeValueAsString(
              new ScheduleListCache.Entry(
                  refreshAt,
                  List.of(
                      new Snapshot(
                          9L,
                          "이전 값",
                          DAY,
                          LocalTime.of(10, 0),
                          LocalTime.of(11, 0),
                          30,
                          5,
                          true))));
    }

    @Test
    @DisplayName("논리 만료: 갱신 시각 전이면 DB를 읽지 않는다")
    void logical_fresh_hit() {
      ScheduleListCache c = cacheWith(ScheduleListCache.Stampede.LOGICAL_EXPIRY, 3);
      given(valueOps.get(LOGICAL_KEY)).willReturn(entryJson(System.currentTimeMillis() + 2_000));

      List<Snapshot> result = c.getOrLoad(1L, DAY, loader);

      assertThat(result).extracting(Snapshot::scheduleId).containsExactly(9L);
      assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("논리 만료: 갱신 시각이 지났고 락을 잡으면 DB에서 새로 읽어 저장한다")
    void logical_stale_lockHolderRefreshes() throws Exception {
      ScheduleListCache c = cacheWith(ScheduleListCache.Stampede.LOGICAL_EXPIRY, 3);
      given(valueOps.get(LOGICAL_KEY)).willReturn(entryJson(System.currentTimeMillis() - 1));
      given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
      given(lock.isHeldByCurrentThread()).willReturn(true);

      List<Snapshot> result = c.getOrLoad(1L, DAY, loader);

      assertThat(result).extracting(Snapshot::scheduleId).containsExactly(100L);
      assertThat(loads).hasValue(1);
      verify(valueOps).set(eq(LOGICAL_KEY), anyString(), eq(Duration.ofSeconds(6)));
      verify(lock).unlock();
    }

    @Test
    @DisplayName("논리 만료: 갱신 시각이 지났지만 다른 요청이 갱신 중이면 기다리지 않고 이전 값을 준다")
    void logical_stale_othersGetStaleWithoutDb() throws Exception {
      ScheduleListCache c = cacheWith(ScheduleListCache.Stampede.LOGICAL_EXPIRY, 3);
      given(valueOps.get(LOGICAL_KEY)).willReturn(entryJson(System.currentTimeMillis() - 1));
      given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

      List<Snapshot> result = c.getOrLoad(1L, DAY, loader);

      assertThat(result).extracting(Snapshot::popupTitle).containsExactly("이전 값");
      assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("논리 만료 · 키 없음: 다른 요청이 대기 시간 안에 채우면 DB를 읽지 않는다")
    void logical_missing_filledWhileWaiting() throws Exception {
      ScheduleListCache c = cacheWith(ScheduleListCache.Stampede.LOGICAL_EXPIRY, 50);
      given(valueOps.get(LOGICAL_KEY))
          .willReturn(null, entryJson(System.currentTimeMillis() + 2_000));
      given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

      List<Snapshot> result = c.getOrLoad(1L, DAY, loader);

      assertThat(result).extracting(Snapshot::scheduleId).containsExactly(9L);
      assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("논리 만료 · 키 없음: 대기 시간이 지나도 비어 있으면 직접 DB를 읽는다")
    void logical_missing_timeoutFallsBackToDb() throws Exception {
      ScheduleListCache c = cacheWith(ScheduleListCache.Stampede.LOGICAL_EXPIRY, 2);
      given(valueOps.get(LOGICAL_KEY)).willReturn(null);
      given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

      List<Snapshot> result = c.getOrLoad(1L, DAY, loader);

      assertThat(result).extracting(Snapshot::scheduleId).containsExactly(100L);
      assertThat(loads).hasValue(1);
      verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("방식 NONE: 놓친 요청은 각자 DB를 읽는다 (락을 쓰지 않음)")
    void none_eachMissLoads() {
      ScheduleListCache c = cacheWith(ScheduleListCache.Stampede.NONE, 3);
      given(valueOps.get(KEY)).willReturn(null);

      c.getOrLoad(1L, DAY, loader);
      c.getOrLoad(1L, DAY, loader);

      assertThat(loads).hasValue(2);
      verify(valueOps, times(2)).set(eq(KEY), anyString(), eq(Duration.ofSeconds(3)));
    }
  }
}
