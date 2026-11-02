package com.popnup.popnupbackend.domain.schedule.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SeatReleaseOutboxTest {

  private OutboxEventRepository repository;
  private ScheduleCapacityCache cache;

  @BeforeEach
  void setUp() {
    repository = mock(OutboxEventRepository.class);
    cache = mock(ScheduleCapacityCache.class);
    given(repository.save(any()))
        .willAnswer(
            inv -> {
              OutboxEvent e = inv.getArgument(0);
              ReflectionTestUtils.setField(e, "id", 77L);
              return e;
            });
  }

  @AfterEach
  void clearSynchronization() {
    TransactionSynchronizationManager.clear();
  }

  // 커밋 직후 반영은 같은 스레드에서 바로 실행하는 실행기로 확인한다
  private SeatReleaseOutbox outbox(boolean enabled, boolean fastPath) {
    return new SeatReleaseOutbox(enabled, fastPath, Runnable::run, repository, cache);
  }

  private static void commit() {
    for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
      s.afterCommit();
    }
  }

  @Test
  @DisplayName("Outbox 켬: 좌석 반환을 회차 id 순 이벤트 하나로 저장한다")
  void enabled_savesOneEvent() {
    outbox(true, false).record(Map.of(20L, 5, 10L, 1));

    ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getEventType()).isEqualTo(OutboxEvent.SEAT_RELEASE);
    assertThat(saved.getValue().getPayload()).isEqualTo("10:1,20:5");
    assertThat(saved.getValue().getPublishedAt()).isNull();
    verify(cache, never()).releaseAfterCommit(any(), anyInt());
  }

  @Test
  @DisplayName("커밋 직후 바로 반영: 커밋 전에는 반영하지 않고, 커밋되면 저장한 이벤트를 곧바로 한 번 반영한다")
  void fastPath_appliesAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();

    outbox(true, true).record(Map.of(10L, 1, 20L, 5));
    verify(cache, never()).applySeatRelease(anyLong(), anyMap());

    commit();

    verify(cache).applySeatRelease(77L, Map.of(10L, 1, 20L, 5));
  }

  @Test
  @DisplayName("커밋 직후 반영이 Redis 오류로 실패해도 예외를 내지 않는다 (이벤트가 남아 중계기가 다시 반영)")
  void fastPath_failureIsSwallowed() {
    TransactionSynchronizationManager.initSynchronization();
    given(cache.applySeatRelease(anyLong(), anyMap()))
        .willThrow(new QueryTimeoutException("Redis command timed out"));

    outbox(true, true).record(Map.of(10L, 1));
    commit();

    verify(repository).save(any());
  }

  @Test
  @DisplayName("롤백되면 커밋 직후 반영도 일어나지 않는다")
  void fastPath_rollback_noApply() {
    TransactionSynchronizationManager.initSynchronization();

    outbox(true, true).record(Map.of(10L, 1));
    for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
      s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    verify(cache, never()).applySeatRelease(anyLong(), anyMap());
  }

  @Test
  @DisplayName("Outbox 끔(도입 전): 이벤트를 남기지 않고 예전처럼 커밋 직후 회차마다 Redis에 반영한다")
  void disabled_releasesAfterCommit() {
    outbox(false, false).record(Map.of(10L, 1, 20L, 5));

    verify(cache).releaseAfterCommit(10L, 1);
    verify(cache).releaseAfterCommit(20L, 5);
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("이벤트 내용은 회차 id:인원 목록으로 저장했다가 그대로 읽힌다")
  void payload_roundTrip() {
    String payload = SeatReleaseOutbox.encode(Map.of(3L, 2, 1L, 1));

    assertThat(payload).isEqualTo("1:1,3:2");
    assertThat(SeatReleaseOutbox.decode(payload))
        .containsExactly(Map.entry(1L, 1), Map.entry(3L, 2));
  }
}
