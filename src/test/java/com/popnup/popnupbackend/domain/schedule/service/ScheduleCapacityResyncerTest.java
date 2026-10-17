package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepositoryCustom.CapacitySnapshot;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Counter;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Restore;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityResyncer.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduleCapacityResyncerTest {

  private static final LocalDate DAY = LocalDate.of(2031, 5, 10);

  private ScheduleRepository repository;
  private ScheduleCapacityCounterStore counterStore;
  private ScheduleCapacityResyncer resyncer;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    repository = mock(ScheduleRepository.class);
    counterStore = mock(ScheduleCapacityCounterStore.class);
    given(counterStore.restoreAll(anyList())).willReturn(true);
    meterRegistry = new SimpleMeterRegistry();
    resyncer = new ScheduleCapacityResyncer(repository, counterStore, meterRegistry);
  }

  /** DB에 회차 하나(id=1, 정원 100, 현재 예약 dbUsed)가 있고 Redis 카운터가 이렇다고 가정 */
  private void scenario(long dbUsed, Long redisMax, Long redisUsed) {
    CapacitySnapshot snap = new CapacitySnapshot(1L, DAY, 100, (int) dbUsed);
    given(repository.findCapacitySnapshots(any(), anyLong(), anyInt()))
        .willAnswer(inv -> (Long) inv.getArgument(1) == 0L ? List.of(snap) : List.of());
    given(counterStore.readCounters(anyList()))
        .willReturn(List.of(new Counter(redisMax, redisUsed)));
  }

  @SuppressWarnings("unchecked")
  private List<Restore> restoredAtLeastOnce() {
    ArgumentCaptor<List<Restore>> captor = ArgumentCaptor.forClass(List.class);
    verify(counterStore, org.mockito.Mockito.atLeastOnce()).restoreAll(captor.capture());
    return captor.getAllValues().stream().flatMap(List::stream).toList();
  }

  @Test
  @DisplayName("키가 없으면 DB 값으로 바로 초기화한다 (필터가 꺼진 상태라 확인 사이클이 필요 없다)")
  void missingKeys_initializedFromDb() {
    scenario(7, null, null);

    Result result = resyncer.resync();

    assertThat(result.initialized()).isEqualTo(1);
    assertThat(restoredAtLeastOnce()).containsExactly(new Restore(1L, DAY, 100, 7));
  }

  @Test
  @DisplayName("DB와 Redis가 일치하면 아무것도 쓰지 않는다")
  void inSync_noWrites() {
    scenario(7, 100L, 7L);

    Result result = resyncer.resync();

    assertThat(result.initialized()).isZero();
    assertThat(result.corrected()).isZero();
    assertThat(result.pendingDrift()).isZero();
    assertThat(restoredAtLeastOnce()).isEmpty();
  }

  @Test
  @DisplayName("어긋남을 처음 보면 바로 고치지 않고 다음 사이클에 확인한다 (진행 중인 예약 보호)")
  void firstObservedDrift_notCorrectedYet() {
    scenario(0, 100L, 50L);

    Result result = resyncer.resync();

    assertThat(result.corrected()).isZero();
    assertThat(result.pendingDrift()).isEqualTo(1);
    assertThat(result.maxAbsDrift()).isEqualTo(50);
    assertThat(restoredAtLeastOnce()).isEmpty();
  }

  @Test
  @DisplayName("같은 어긋남이 두 사이클 연속 같은 값이면 DB 값으로 보정한다")
  void sameDriftTwice_corrected() {
    scenario(0, 100L, 50L);

    resyncer.resync();
    Result second = resyncer.resync();

    assertThat(second.corrected()).isEqualTo(1);
    assertThat(restoredAtLeastOnce()).containsExactly(new Restore(1L, DAY, 100, 0));
  }

  @Test
  @DisplayName("두 번째 관측에서 값이 바뀌었으면 진행 중인 예약으로 보고 보정하지 않는다")
  void changingDrift_notCorrected() {
    scenario(0, 100L, 50L);
    resyncer.resync();
    scenario(0, 100L, 51L); // 그 사이에 Redis 값이 또 변함

    Result second = resyncer.resync();

    assertThat(second.corrected()).isZero();
    assertThat(second.pendingDrift()).isEqualTo(1);
    verify(counterStore, never())
        .restoreAll(org.mockito.ArgumentMatchers.argThat(l -> !l.isEmpty()));
  }

  @Test
  @DisplayName("중간에 일치로 돌아오면 확인 대기를 지운다 (다시 어긋나면 처음부터 확인)")
  void backToSync_clearsPending() {
    scenario(0, 100L, 50L);
    resyncer.resync();
    scenario(0, 100L, 0L); // 일치로 돌아옴 (일시적 차이였음)
    resyncer.resync();
    scenario(0, 100L, 50L); // 다시 어긋남

    Result third = resyncer.resync();

    assertThat(third.corrected()).isZero();
    assertThat(third.pendingDrift()).isEqualTo(1);
  }

  @Test
  @DisplayName("정원(max)이 DB와 다르면 어긋남으로 보고 같은 규칙으로 보정한다")
  void maxMismatch_corrected() {
    scenario(5, 80L, 5L);

    resyncer.resync();
    Result second = resyncer.resync();

    assertThat(second.corrected()).isEqualTo(1);
    assertThat(restoredAtLeastOnce()).containsExactly(new Restore(1L, DAY, 100, 5));
  }

  @Test
  @DisplayName("Redis 장애 시에는 아무것도 바꾸지 않고 예외도 던지지 않는다")
  void redisDown_nothingChanged() {
    CapacitySnapshot snap = new CapacitySnapshot(1L, DAY, 100, 0);
    given(repository.findCapacitySnapshots(any(), anyLong(), anyInt()))
        .willAnswer(inv -> (Long) inv.getArgument(1) == 0L ? List.of(snap) : List.of());
    given(counterStore.readCounters(anyList())).willReturn(null);

    Result result = resyncer.resync();

    assertThat(result.initialized()).isZero();
    assertThat(result.corrected()).isZero();
    verify(counterStore, never()).restoreAll(anyList());
  }
}
