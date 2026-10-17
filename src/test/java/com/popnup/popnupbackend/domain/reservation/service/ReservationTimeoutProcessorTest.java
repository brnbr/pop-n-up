package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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
            reservationRepository, reservationCancelManager, meterRegistry);
    ReflectionTestUtils.setField(processor, "chunkSize", 2);
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
  @DisplayName("청크가 가득 차 있으면 다음 청크를 이어서 조회하고, 모든 예약을 만료 처리한다")
  void expireUnpaid_processesAllChunks() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), anyLong(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of(r3));
    given(reservationCancelManager.expirePaymentTimeout(any())).willReturn(true);

    processor.expireUnpaidReservations();

    verify(reservationRepository, times(2))
        .findPendingReservationsChunk(any(), any(), anyLong(), eq(2));
    verify(reservationCancelManager).expirePaymentTimeout(1L);
    verify(reservationCancelManager).expirePaymentTimeout(2L);
    verify(reservationCancelManager).expirePaymentTimeout(3L);
  }

  @Test
  @DisplayName("한 건이 실패해도 나머지는 계속 처리하고, 실패 지표를 올린다")
  void failureOfOne_doesNotStopOthers_andCountsFailure() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), anyLong(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of());
    given(reservationCancelManager.expirePaymentTimeout(1L))
        .willThrow(new IllegalStateException("처리 실패"));
    given(reservationCancelManager.expirePaymentTimeout(2L)).willReturn(true);

    processor.expireUnpaidReservations();

    verify(reservationCancelManager).expirePaymentTimeout(2L);
    assertThat(meterRegistry.counter("reservation.expire.failure", "label", PAY_LABEL).count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("계속 실패하는 예약이 청크를 가득 채워도 커서가 마지막 id 뒤로 넘어가, 같은 예약을 다시 집지 않고 끝난다")
  void persistentFailures_doNotLoopForever() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), eq(0L), eq(2)))
        .willReturn(List.of(r1, r2));
    given(reservationRepository.findPendingReservationsChunk(any(), any(), eq(2L), eq(2)))
        .willReturn(List.of());
    given(reservationCancelManager.expirePaymentTimeout(any()))
        .willThrow(new IllegalStateException("계속 실패"));

    processor.expireUnpaidReservations();

    verify(reservationCancelManager, times(1)).expirePaymentTimeout(1L);
    verify(reservationCancelManager, times(1)).expirePaymentTimeout(2L);
    verify(reservationRepository).findPendingReservationsChunk(any(), any(), eq(2L), eq(2));
    assertThat(meterRegistry.counter("reservation.expire.failure", "label", PAY_LABEL).count())
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName("결제 대기 기준 시각은 지금에서 결제 대기 시간(초)을 뺀 값이다")
  void expireUnpaid_deadlineIsNowMinusTimeout() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), anyLong(), eq(2)))
        .willReturn(List.of());
    LocalDateTime before = LocalDateTime.now();

    processor.expireUnpaidReservations();

    ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reservationRepository)
        .findPendingReservationsChunk(
            eq(ReservationStatus.PENDING), deadline.capture(), eq(0L), eq(2));
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
