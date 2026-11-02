package com.popnup.popnupbackend.domain.schedule.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class OutboxRelayTest {

  private OutboxEventRepository repository;
  private SeatReleaseOutbox outbox;
  private SimpleMeterRegistry meters;
  private OutboxRelay relay;

  @BeforeEach
  void setUp() {
    repository = mock(OutboxEventRepository.class);
    outbox = mock(SeatReleaseOutbox.class);
    meters = new SimpleMeterRegistry();
    relay =
        new OutboxRelay(repository, outbox, mock(PlatformTransactionManager.class), meters, 3, 24);
  }

  private static OutboxEvent event(long id, LocalDateTime createdAt) {
    OutboxEvent e = OutboxEvent.of(OutboxEvent.SEAT_RELEASE, id + ":1", createdAt);
    ReflectionTestUtils.setField(e, "id", id);
    return e;
  }

  @Test
  @DisplayName("미처리 이벤트를 Redis에 반영하고 결과별로 처리됨을 남긴다 (이미 반영된 이벤트는 DUPLICATE)")
  void appliesAndMarks() {
    LocalDateTime now = LocalDateTime.now();
    OutboxEvent first = event(1L, now);
    OutboxEvent second = event(2L, now);
    given(repository.claimUnpublished(3)).willReturn(List.of(first, second));
    given(outbox.apply(first)).willReturn(true);
    given(outbox.apply(second)).willReturn(false);

    assertThat(relay.relayBatch()).isEqualTo(2);

    verify(repository).markPublished(eq(List.of(1L)), eq(OutboxRelay.APPLIED), any());
    verify(repository).markPublished(eq(List.of(2L)), eq(OutboxRelay.DUPLICATE), any());
    assertThat(meters.timer("outbox.delivery").count()).isEqualTo(2);
  }

  @Test
  @DisplayName("Redis 장애로 오래 밀린 이벤트도 건너뛰지 않고 반영한다 (반환을 빠뜨리면 팔 수 있는 좌석이 매진으로 막힘)")
  void oldEvent_stillApplied() {
    OutboxEvent old = event(1L, LocalDateTime.now().minusMinutes(5));
    given(repository.claimUnpublished(3)).willReturn(List.of(old));
    given(outbox.apply(old)).willReturn(true);

    relay.relayBatch();

    verify(repository).markPublished(eq(List.of(1L)), eq(OutboxRelay.APPLIED), any());
  }

  @Test
  @DisplayName("Redis 오류면 그 묶음의 나머지는 미처리로 두고 멈춘다 (다음 주기에 다시)")
  void redisFailure_leavesRestUnpublished() {
    LocalDateTime now = LocalDateTime.now();
    OutboxEvent first = event(1L, now);
    OutboxEvent second = event(2L, now);
    OutboxEvent third = event(3L, now);
    given(repository.claimUnpublished(3)).willReturn(List.of(first, second, third));
    given(outbox.apply(first)).willReturn(true);
    given(outbox.apply(second)).willThrow(new QueryTimeoutException("Redis command timed out"));

    assertThat(relay.relayBatch()).isEqualTo(-1);

    verify(repository).markPublished(eq(List.of(1L)), eq(OutboxRelay.APPLIED), any());
    verify(outbox, never()).apply(third);
  }

  @Test
  @DisplayName("묶음이 가득 차면 이어서 가져오고, 이벤트가 없으면 미처리 수를 세지 않는다")
  void relay_drainsFullBatches() {
    LocalDateTime now = LocalDateTime.now();
    given(repository.claimUnpublished(3))
        .willReturn(List.of(event(1L, now), event(2L, now), event(3L, now)))
        .willReturn(List.of(event(4L, now)));
    given(outbox.apply(any())).willReturn(true);
    given(repository.countUnpublished()).willReturn(0L);

    relay.relay();

    verify(outbox, org.mockito.Mockito.times(4)).apply(any());
    verify(repository).countUnpublished();

    org.mockito.Mockito.clearInvocations(repository);
    given(repository.claimUnpublished(anyInt())).willReturn(List.of());
    relay.relay();
    verify(repository, never()).countUnpublished();
  }
}
