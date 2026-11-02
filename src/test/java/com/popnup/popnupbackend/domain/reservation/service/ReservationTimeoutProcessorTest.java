package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor.ExpiryMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class ReservationTimeoutProcessorTest {

  private static final String PAY_LABEL = "결제 미완료 만료";

  private ReservationRepository reservationRepository;
  private ReservationCancelManager reservationCancelManager;
  private SimpleMeterRegistry meterRegistry;
  private ReservationTimeoutProcessor processor;
  private Reservation r1;
  private Reservation r2;
  private Reservation r3;

  @BeforeEach
  void setUp() {
    reservationRepository = mock(ReservationRepository.class);
    reservationCancelManager = mock(ReservationCancelManager.class);
    meterRegistry = new SimpleMeterRegistry();
    processor =
        new ReservationTimeoutProcessor(
            reservationRepository,
            reservationCancelManager,
            meterRegistry,
            mock(PlatformTransactionManager.class));
    ReflectionTestUtils.setField(processor, "chunkSize", 2);
    ReflectionTestUtils.setField(processor, "workers", 1);
    ReflectionTestUtils.setField(processor, "expiryMode", ExpiryMode.CHUNK);
    ReflectionTestUtils.setField(processor, "paymentTimeoutSeconds", 600L);
    r1 = reservation(1L);
    r2 = reservation(2L);
    r3 = reservation(3L);
  }

  private Reservation reservation(long id) {
    Reservation reservation = mock(Reservation.class);
    given(reservation.getId()).willReturn(id);
    return reservation;
  }

  @Test
  @DisplayName("청크 방식: 가득 찬 청크가 나오는 동안 계속 가져와 청크마다 한 번에 만료한다")
  void chunkMode_expiresEachChunkAtOnce() {
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of(r3));

    processor.expireUnpaidReservations();

    verify(reservationRepository, times(2)).claimExpiredPendingChunk(any(), anyList(), eq(2));
    verify(reservationCancelManager).expirePaymentTimeoutChunk(List.of(r1, r2));
    verify(reservationCancelManager).expirePaymentTimeoutChunk(List.of(r3));
    verify(reservationCancelManager, never()).expirePaymentTimeout(any());
  }

  @Test
  @DisplayName("청크 처리가 실패하면 그 청크만 건별로 다시 처리하고, 대체 처리 지표를 올린다")
  void chunkFailure_fallsBackToItemByItem() {
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of());
    given(reservationCancelManager.expirePaymentTimeoutChunk(anyList()))
        .willThrow(new IllegalStateException("청크 실패"));
    given(reservationCancelManager.expirePaymentTimeout(any())).willReturn(true);

    processor.expireUnpaidReservations();

    verify(reservationCancelManager).expirePaymentTimeout(1L);
    verify(reservationCancelManager).expirePaymentTimeout(2L);
    assertThat(
            meterRegistry.counter("reservation.expire.chunk.fallback", "label", PAY_LABEL).count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("건별로도 실패한 예약은 실패 지표를 올리고, 이번 실행에서 다시 집지 않아 끝난다")
  void persistentFailure_isExcludedFromNextClaim() {
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of());
    given(reservationCancelManager.expirePaymentTimeoutChunk(anyList()))
        .willThrow(new IllegalStateException("청크 실패"));
    given(reservationCancelManager.expirePaymentTimeout(1L))
        .willThrow(new IllegalStateException("계속 실패"));
    given(reservationCancelManager.expirePaymentTimeout(2L)).willReturn(true);

    processor.expireUnpaidReservations();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<Long>> excluded = ArgumentCaptor.forClass(Collection.class);
    verify(reservationRepository, times(2))
        .claimExpiredPendingChunk(any(), excluded.capture(), eq(2));
    assertThat(excluded.getAllValues().get(0)).isEmpty();
    assertThat(excluded.getAllValues().get(1)).containsExactly(1L);
    assertThat(meterRegistry.counter("reservation.expire.failure", "label", PAY_LABEL).count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("건별 방식(측정용): 청크로 가져온 뒤 건마다 만료하고, 청크 단위 만료는 쓰지 않는다")
  void perItemMode_expiresOneByOne() {
    ReflectionTestUtils.setField(processor, "expiryMode", ExpiryMode.PER_ITEM);
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of(r3));
    given(reservationCancelManager.expirePaymentTimeout(any())).willReturn(true);

    processor.expireUnpaidReservations();

    verify(reservationCancelManager).expirePaymentTimeout(1L);
    verify(reservationCancelManager).expirePaymentTimeout(2L);
    verify(reservationCancelManager).expirePaymentTimeout(3L);
    verify(reservationCancelManager, never()).expirePaymentTimeoutChunk(anyList());
  }

  @Test
  @DisplayName("워커 여러 개: 워커들이 청크를 나눠 가져가 모든 예약을 한 번씩만 만료한다")
  void multipleWorkers_shareChunksWithoutDuplicates() {
    ReflectionTestUtils.setField(processor, "workers", 3);
    Reservation r4 = reservation(4L);
    Reservation r5 = reservation(5L);
    Queue<List<Reservation>> chunks =
        new ConcurrentLinkedQueue<>(List.of(List.of(r1, r2), List.of(r3, r4), List.of(r5)));
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(2)))
        .willAnswer(
            invocation -> {
              List<Reservation> next = chunks.poll();
              return next == null ? List.of() : next;
            });

    processor.expireUnpaidReservations();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Reservation>> expired = ArgumentCaptor.forClass(List.class);
    verify(reservationCancelManager, times(3)).expirePaymentTimeoutChunk(expired.capture());
    List<Long> expiredIds = new ArrayList<>();
    expired.getAllValues().forEach(chunk -> chunk.forEach(r -> expiredIds.add(r.getId())));
    assertThat(expiredIds).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  @DisplayName("방식 · 청크 크기 · 워커 수를 넘기면 설정값 대신 그 값으로 돌고, 처리 건수와 트랜잭션 시간을 돌려준다")
  void explicitOptions_overrideConfigAndReportResult() {
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(3)))
        .willReturn(List.of(r1, r2, r3))
        .willReturn(List.of());
    given(reservationCancelManager.expirePaymentTimeout(any())).willReturn(true);

    ReservationTimeoutProcessor.ExpiryResult result =
        processor.expireUnpaidReservations(ExpiryMode.PER_ITEM, 3, 1);

    assertThat(result.mode()).isEqualTo(ExpiryMode.PER_ITEM);
    assertThat(result.chunkSize()).isEqualTo(3);
    assertThat(result.processed()).isEqualTo(3);
    assertThat(result.failed()).isZero();
    assertThat(result.maxTxMs()).isGreaterThanOrEqualTo(0);
    assertThat(meterRegistry.timer("reservation.expire.tx", "kind", "item").count()).isEqualTo(3);
    assertThat(meterRegistry.timer("reservation.expire.tx", "kind", "claim").count()).isEqualTo(2);
    verify(reservationCancelManager, never()).expirePaymentTimeoutChunk(anyList());
  }

  @Test
  @DisplayName("청크 방식은 청크 트랜잭션마다 시간을 남긴다")
  void chunkMode_recordsTransactionTime() {
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of(r3));

    ReservationTimeoutProcessor.ExpiryResult result = processor.expireUnpaidReservations();

    assertThat(result.processed()).isEqualTo(3);
    assertThat(meterRegistry.timer("reservation.expire.tx", "kind", "chunk").count()).isEqualTo(2);
  }

  @Test
  @DisplayName("결제 대기 기준 시각은 지금에서 결제 대기 시간(초)을 뺀 값이다")
  void expireUnpaid_deadlineIsNowMinusTimeout() {
    given(reservationRepository.claimExpiredPendingChunk(any(), anyList(), eq(2)))
        .willReturn(List.of());
    LocalDateTime before = LocalDateTime.now();

    processor.expireUnpaidReservations();

    ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reservationRepository).claimExpiredPendingChunk(deadline.capture(), anyList(), eq(2));
    assertThat(deadline.getValue())
        .isBetween(before.minusSeconds(601), LocalDateTime.now().minusSeconds(599));
  }

  @Test
  @DisplayName("노쇼 만료: 시작 후 20분이 지난 회차의 확정 예약을 한 번의 벌크 업데이트로 만료하고, 건별 취소 처리(정원 복구)는 하지 않는다")
  void expirePast_bulkUpdate_withoutCapacityRestore() {
    given(reservationRepository.expirePastConfirmedReservations(any())).willReturn(3L);
    LocalDateTime before = LocalDateTime.now();

    processor.expirePastReservations();

    ArgumentCaptor<LocalDateTime> startedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reservationRepository).expirePastConfirmedReservations(startedBefore.capture());
    assertThat(startedBefore.getValue())
        .isBetween(before.minusMinutes(20), LocalDateTime.now().minusMinutes(20));
    verifyNoInteractions(reservationCancelManager);
  }
}
