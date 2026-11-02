package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache.ReserveResult;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Restore;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScheduleCapacityCacheTest {

  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOps;
  private ScheduleCapacityCounterStore counterStore;
  private ScheduleCapacityCache cache;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    given(redisTemplate.opsForValue()).willReturn(valueOps);
    counterStore = mock(ScheduleCapacityCounterStore.class);
    cache = new ScheduleCapacityCache(redisTemplate, counterStore);
    ReflectionTestUtils.setField(cache, "inProgressTtlMs", 10_000L);
  }

  @AfterEach
  void clearSynchronization() {
    TransactionSynchronizationManager.clear();
  }

  // multiGet 순서: [max, used]
  private void redisHas(String max, String used) {
    given(valueOps.multiGet(anyList())).willReturn(Arrays.asList(max, used));
  }

  @Nested
  @DisplayName("정원 마감 선확인 [isSoldOut]")
  class IsSoldOutTest {

    @Test
    @DisplayName("마감: 사용량 + 요청 인원이 정원을 넘으면 true")
    void soldOut_whenOverCapacity() {
      redisHas("100", "100");

      assertThat(cache.isSoldOut(1L, 1)).isTrue();
    }

    @Test
    @DisplayName("경계: 사용량 + 요청 인원이 정확히 정원이면 마감이 아니다 (마지막 자리)")
    void notSoldOut_atExactCapacity() {
      redisHas("100", "99");

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }

    @Test
    @DisplayName("fail-open: 키가 없으면 false (일괄 등록 이전에 만든 회차 등, DB가 판단)")
    void notSoldOut_whenKeysMissing() {
      redisHas(null, null);

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }

    @Test
    @DisplayName("fail-open: Redis 장애 시 예외를 던지지 않고 false (선확인이 예약을 막으면 안 된다)")
    void notSoldOut_whenRedisFails() {
      given(valueOps.multiGet(anyList())).willThrow(new RuntimeException("redis down"));

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }
  }

  @Nested
  @DisplayName("정원 선점 · 복구 [tryReserve · releaseNow]")
  class ReserveAndReleaseTest {

    private static final List<String> RESERVE_KEYS =
        List.of(
            "schedule:capacity:max:1",
            "schedule:capacity:used:1",
            "schedule:booking:in-progress:1:7");

    @SuppressWarnings("unchecked")
    private void scriptReturns(Long result) {
      given(redisTemplate.execute(any(RedisScript.class), eq(RESERVE_KEYS), eq("1"), eq("10000")))
          .willReturn(result);
    }

    @Test
    @DisplayName("스크립트 한 번에 정원 · 사용량 · 회원 진행 중 표시 키와 표시 유효 시간을 넘긴다")
    void passesInProgressKeyAndTtlInOneCall() {
      scriptReturns(1L);

      assertThat(cache.tryReserve(1L, 7L, 1)).isEqualTo(ReserveResult.RESERVED);
    }

    @Test
    @DisplayName("같은 회원의 예약이 진행 중이면 IN_PROGRESS (자리를 잡지 않음)")
    void inProgress() {
      scriptReturns(2L);

      assertThat(cache.tryReserve(1L, 7L, 1)).isEqualTo(ReserveResult.IN_PROGRESS);
    }

    @Test
    @DisplayName("정원 초과면 SOLD_OUT")
    void soldOut() {
      scriptReturns(0L);

      assertThat(cache.tryReserve(1L, 7L, 1)).isEqualTo(ReserveResult.SOLD_OUT);
    }

    @Test
    @DisplayName("fail-open: 정원 키가 없으면 RESERVED (DB가 판단)")
    void keyMissing_reserved() {
      scriptReturns(-1L);

      assertThat(cache.tryReserve(1L, 7L, 1)).isEqualTo(ReserveResult.RESERVED);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("fail-open: Redis 장애면 예외 없이 RESERVED (DB 유니크 제약이 중복을 막는다)")
    void redisFails_reserved() {
      given(redisTemplate.execute(any(RedisScript.class), eq(RESERVE_KEYS), eq("1"), eq("10000")))
          .willThrow(new RuntimeException("redis down"));

      assertThat(cache.tryReserve(1L, 7L, 1)).isEqualTo(ReserveResult.RESERVED);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("예약 실패를 되돌릴 때는 사용량과 회원 진행 중 표시를 함께 넘긴다 (호출 한 번)")
    void releaseWithMember_passesInProgressKey() {
      cache.releaseNow(1L, 7L, 1);

      verify(redisTemplate, times(1))
          .execute(
              any(RedisScript.class),
              eq(List.of("schedule:capacity:used:1", "schedule:booking:in-progress:1:7")),
              eq("1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("취소 · 만료의 정원 복구는 사용량 키만 넘긴다 (진행 중 표시를 건드리지 않음)")
    void releaseWithoutMember_passesUsedKeyOnly() {
      cache.releaseNow(1L, 1);

      verify(redisTemplate, times(1))
          .execute(any(RedisScript.class), eq(List.of("schedule:capacity:used:1")), eq("1"));
    }
  }

  @Nested
  @DisplayName("회차 정원 키 초기화 [initAfterCommit]")
  class InitAfterCommitTest {

    private final LocalDate date = LocalDate.of(2031, 5, 10);

    private Schedule schedule(long id, int maxCapacity) {
      Schedule schedule =
          Schedule.createSchedule(
              mock(Popup.class), date, LocalTime.of(10, 0), LocalTime.of(11, 0), maxCapacity);
      ReflectionTestUtils.setField(schedule, "id", id);
      return schedule;
    }

    @Test
    @DisplayName("트랜잭션 밖이면 바로, 여러 회차를 한 번에 쓴다 (정원 + 사용량 0)")
    void outsideTransaction_writesAllAtOnce() {
      given(counterStore.restoreAll(anyList())).willReturn(true);

      cache.initAfterCommit(List.of(schedule(1L, 30), schedule(2L, 20)));

      verify(counterStore, times(1))
          .restoreAll(List.of(new Restore(1L, date, 30, 0), new Restore(2L, date, 20, 0)));
    }

    @Test
    @DisplayName("트랜잭션 안이면 커밋 전에는 쓰지 않고, 커밋 뒤에 한 번만 쓴다")
    void insideTransaction_writesOnlyAfterCommit() {
      given(counterStore.restoreAll(anyList())).willReturn(true);
      TransactionSynchronizationManager.initSynchronization();

      cache.initAfterCommit(List.of(schedule(1L, 30)));
      verify(counterStore, never()).restoreAll(anyList());

      for (TransactionSynchronization synchronization :
          TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCommit();
      }

      verify(counterStore, times(1)).restoreAll(List.of(new Restore(1L, date, 30, 0)));
    }

    @Test
    @DisplayName("롤백되면 Redis에 쓰지 않는다 (없는 회차의 키가 남지 않는다)")
    void rollback_neverWrites() {
      TransactionSynchronizationManager.initSynchronization();

      cache.initAfterCommit(List.of(schedule(1L, 30)));
      for (TransactionSynchronization synchronization :
          TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
      }

      verify(counterStore, never()).restoreAll(anyList());
    }

    @Test
    @DisplayName("쓰기가 실패해도 예외를 던지지 않는다 (DB만으로 동작하고 재동기화가 채운다)")
    void writeFailure_doesNotThrow() {
      given(counterStore.restoreAll(anyList())).willReturn(false);

      assertThatCode(() -> cache.initAfterCommit(List.of(schedule(1L, 30))))
          .doesNotThrowAnyException();
    }
  }
}
