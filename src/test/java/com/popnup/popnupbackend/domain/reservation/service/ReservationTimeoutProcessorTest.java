package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
  private Reservation r7;

  @BeforeEach
  void setUp() {
    reservationRepository = mock(ReservationRepository.class);
    reservationCancelManager = mock(ReservationCancelManager.class);
    meterRegistry = new SimpleMeterRegistry();
    processor =
        new ReservationTimeoutProcessor(
            reservationRepository,
            reservationCancelManager,
            new ReservationChunkClaimer(),
            meterRegistry);
    ReflectionTestUtils.setField(processor, "chunkSize", 2);
    ReflectionTestUtils.setField(processor, "workerCount", 1);
    ReflectionTestUtils.setField(processor, "paymentTimeoutSeconds", 600L);
    r1 = reservation(1L);
    r2 = reservation(2L);
    r3 = reservation(3L);
    r7 = reservation(7L);
  }

  private Reservation reservation(long id) {
    Reservation reservation = mock(Reservation.class);
    given(reservation.getId()).willReturn(id);
    return reservation;
  }

  @Test
  @DisplayName("단일 워커: 청크가 가득 차 있으면 다음 청크를 이어서 조회하고, 모든 예약을 만료 처리한다")
  void singleWorker_payTimeout_processesAllChunks() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of(r3));
    given(reservationCancelManager.expirePaymentTimeout(any())).willReturn(true);

    processor.payTimeOut();

    verify(reservationRepository, times(2)).findPendingReservationsChunk(any(), any(), eq(2));
    verify(reservationCancelManager).expirePaymentTimeout(1L);
    verify(reservationCancelManager).expirePaymentTimeout(2L);
    verify(reservationCancelManager).expirePaymentTimeout(3L);
    verify(reservationRepository, never())
        .findPendingReservationsChunkForUpdate(any(), any(), eq(2));
  }

  @Test
  @DisplayName("단일 워커: 조회된 청크가 청크 크기보다 작으면 더 조회하지 않고 끝낸다")
  void singleWorker_stopsWhenChunkIsNotFull() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), eq(2)))
        .willReturn(List.of(r1));

    processor.payTimeOut();

    verify(reservationRepository, times(1)).findPendingReservationsChunk(any(), any(), eq(2));
    verify(reservationCancelManager).expirePaymentTimeout(1L);
  }

  @Test
  @DisplayName("단일 워커: 대상이 없으면 아무것도 처리하지 않는다")
  void singleWorker_emptyChunk_doesNothing() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), eq(2)))
        .willReturn(List.of());

    processor.payTimeOut();

    verify(reservationCancelManager, never()).expirePaymentTimeout(any());
  }

  @Test
  @DisplayName("한 건이 실패해도 나머지는 계속 처리하고, 실패 지표를 올린다")
  void failureOfOne_doesNotStopOthers_andCountsFailure() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of());
    given(reservationCancelManager.expirePaymentTimeout(1L))
        .willThrow(new IllegalStateException("처리 실패"));
    given(reservationCancelManager.expirePaymentTimeout(2L)).willReturn(true);

    processor.payTimeOut();

    verify(reservationCancelManager).expirePaymentTimeout(2L);
    assertThat(meterRegistry.counter("reservation.expire.failure", "label", PAY_LABEL).count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("결제 대기 기준 시각은 지금에서 결제 대기 시간(초)을 뺀 값이다")
  void payTimeOut_deadlineIsNowMinusTimeout() {
    given(reservationRepository.findPendingReservationsChunk(any(), any(), eq(2)))
        .willReturn(List.of());
    LocalDateTime before = LocalDateTime.now();

    processor.payTimeOut();

    ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reservationRepository)
        .findPendingReservationsChunk(eq(ReservationStatus.PENDING), deadline.capture(), eq(2));
    assertThat(deadline.getValue())
        .isBetween(before.minusSeconds(601), LocalDateTime.now().minusSeconds(599));
  }

  @Test
  @DisplayName("멀티 워커: 잠금 조회(SKIP LOCKED)를 쓰고, 각 예약을 정확히 한 번만 처리한다")
  void multiWorker_usesLockedQuery_processesEachOnce() {
    ReflectionTestUtils.setField(processor, "workerCount", 3);
    given(reservationRepository.findPendingReservationsChunkForUpdate(any(), any(), eq(2)))
        .willReturn(List.of(r1, r2))
        .willReturn(List.of());
    given(reservationCancelManager.expirePaymentTimeout(any())).willReturn(true);

    processor.payTimeOut();

    verify(reservationCancelManager, times(1)).expirePaymentTimeout(1L);
    verify(reservationCancelManager, times(1)).expirePaymentTimeout(2L);
    verify(reservationRepository, never()).findPendingReservationsChunk(any(), any(), eq(2));
  }

  @Test
  @DisplayName("미방문 만료(단일 워커): 지난 회차의 확정 예약을 조회해 미방문 만료 처리한다")
  void singleWorker_expirePast_usesExpireNoShow() {
    given(reservationRepository.findExpiredReservationsChunk(any(), any(), eq(2)))
        .willReturn(List.of(r7));
    given(reservationCancelManager.expireNoShow(7L)).willReturn(true);

    processor.expirePastReservations();

    verify(reservationRepository)
        .findExpiredReservationsChunk(any(LocalDate.class), any(LocalTime.class), eq(2));
    verify(reservationCancelManager).expireNoShow(7L);
    verify(reservationCancelManager, never()).expirePaymentTimeout(any());
  }

  @Test
  @DisplayName("미방문 만료(멀티 워커): 잠금 조회를 쓴다")
  void multiWorker_expirePast_usesLockedQuery() {
    ReflectionTestUtils.setField(processor, "workerCount", 2);
    given(reservationRepository.findExpiredReservationsChunkForUpdate(any(), any(), eq(2)))
        .willReturn(List.of(r7))
        .willReturn(List.of());
    given(reservationCancelManager.expireNoShow(7L)).willReturn(true);

    processor.expirePastReservations();

    verify(reservationCancelManager, times(1)).expireNoShow(7L);
    verify(reservationRepository, never()).findExpiredReservationsChunk(any(), any(), eq(2));
  }
}
