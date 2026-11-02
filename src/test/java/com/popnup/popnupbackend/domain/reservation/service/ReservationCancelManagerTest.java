package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.outbox.SeatReleaseOutbox;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

class ReservationCancelManagerTest {

  private static final Long RESERVATION_ID = 1L;
  private static final Long SCHEDULE_ID = 10L;
  private static final int PERSON_COUNT = 3;

  private ScheduleRepository scheduleRepository;
  private ReservationRepository reservationRepository;
  private SeatReleaseOutbox seatReleaseOutbox;
  private ReservationCancelManager manager;

  @BeforeEach
  void setUp() {
    scheduleRepository = mock(ScheduleRepository.class);
    reservationRepository = mock(ReservationRepository.class);
    seatReleaseOutbox = mock(SeatReleaseOutbox.class);
    manager =
        new ReservationCancelManager(scheduleRepository, reservationRepository, seatReleaseOutbox);
  }

  // 잠가서 읽은 예약의 현재 상태
  private void statusIs(ReservationStatus status) {
    Reservation reservation = mock(Reservation.class);
    Schedule schedule = mock(Schedule.class);
    given(schedule.getId()).willReturn(SCHEDULE_ID);
    given(reservation.getSchedule()).willReturn(schedule);
    given(reservation.getPersonCount()).willReturn(PERSON_COUNT);
    given(reservation.getStatus()).willReturn(status);
    given(reservationRepository.findByIdWithPessimisticLock(RESERVATION_ID))
        .willReturn(Optional.of(reservation));
  }

  private void updateResult(ReservationStatus newStatus, List<ReservationStatus> from, int rows) {
    given(reservationRepository.tryUpdateStatus(RESERVATION_ID, newStatus, from)).willReturn(rows);
  }

  private void assertErrorCode(Runnable action, Object expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ServiceException.class)
        .satisfies(e -> assertThat(((ServiceException) e).getErrorCode()).isEqualTo(expected));
  }

  @Test
  @DisplayName("취소 성공: 예약 행을 잠가 확인하고, 좌석을 먼저 돌려준 뒤 CANCELED로 바꾼다 (잠금 순서 예약 → 회차)")
  void cancel_success_locksReservationThenSchedule() {
    statusIs(ReservationStatus.CONFIRMED);
    given(scheduleRepository.tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT)).willReturn(1);
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        1);

    assertThat(manager.cancel(RESERVATION_ID)).isTrue();

    InOrder order = inOrder(reservationRepository, scheduleRepository);
    order.verify(reservationRepository).findByIdWithPessimisticLock(RESERVATION_ID);
    order.verify(scheduleRepository).tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT);
    order
        .verify(reservationRepository)
        .tryUpdateStatus(
            RESERVATION_ID,
            ReservationStatus.CANCELED,
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED));
    verify(seatReleaseOutbox).record(SCHEDULE_ID, PERSON_COUNT);
  }

  @Test
  @DisplayName("결제 만료: PENDING에서 EXPIRED로만 바꾼다")
  void expirePaymentTimeout_onlyFromPending() {
    statusIs(ReservationStatus.PENDING);
    given(scheduleRepository.tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT)).willReturn(1);
    updateResult(ReservationStatus.EXPIRED, List.of(ReservationStatus.PENDING), 1);

    assertThat(manager.expirePaymentTimeout(RESERVATION_ID)).isTrue();

    verify(seatReleaseOutbox).record(SCHEDULE_ID, PERSON_COUNT);
  }

  @Test
  @DisplayName("예약이 없으면 RESERVATION_NOT_FOUND이고 좌석 · 상태를 건드리지 않는다")
  void reservationMissing_notFound() {
    given(reservationRepository.findByIdWithPessimisticLock(RESERVATION_ID))
        .willReturn(Optional.empty());

    assertErrorCode(
        () -> manager.cancel(RESERVATION_ID), ReservationErrorCode.RESERVATION_NOT_FOUND);

    verify(scheduleRepository, never()).tryDecreaseCapacity(anyLong(), anyInt());
    verify(reservationRepository, never()).tryUpdateStatus(anyLong(), any(), any());
  }

  @Test
  @DisplayName("좌석 복구가 0건이면 INVALID_CANCEL_COUNT이고 상태와 Redis 카운터는 건드리지 않는다")
  void restoreFails_invalidCancelCount() {
    statusIs(ReservationStatus.CONFIRMED);
    given(scheduleRepository.tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT)).willReturn(0);

    assertErrorCode(() -> manager.cancel(RESERVATION_ID), ScheduleErrorCode.INVALID_CANCEL_COUNT);

    verify(reservationRepository, never()).tryUpdateStatus(anyLong(), any(), any());
    verify(seatReleaseOutbox, never()).record(anyLong(), anyInt());
  }

  @Test
  @DisplayName("잠근 뒤에도 상태 변경이 0건이면 INVALID_RESERVATION_STATUS를 던져 돌려준 좌석까지 되돌리게 한다")
  void lockedButUpdateFails_throwsToRollBack() {
    statusIs(ReservationStatus.CONFIRMED);
    given(scheduleRepository.tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT)).willReturn(1);
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        0);

    assertErrorCode(
        () -> manager.cancel(RESERVATION_ID), ReservationErrorCode.INVALID_RESERVATION_STATUS);

    verify(seatReleaseOutbox, never()).record(anyLong(), anyInt());
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"CANCELED", "EXPIRED"})
  @DisplayName("이미 CANCELED/EXPIRED면 false를 반환하고 좌석 · 상태는 건드리지 않는다")
  void alreadyTerminal_returnsFalse(ReservationStatus current) {
    statusIs(current);

    assertThat(manager.cancel(RESERVATION_ID)).isFalse();

    verify(scheduleRepository, never()).tryDecreaseCapacity(anyLong(), anyInt());
    verify(reservationRepository, never()).tryUpdateStatus(anyLong(), any(), any());
    verify(seatReleaseOutbox, never()).record(anyLong(), anyInt());
  }

  @Test
  @DisplayName("이미 USED면 ALREADY_PROCESSED_RESERVATION")
  void alreadyUsed_alreadyProcessed() {
    statusIs(ReservationStatus.USED);

    assertErrorCode(
        () -> manager.cancel(RESERVATION_ID), ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
  }

  @Test
  @DisplayName("허용 상태가 아니면 INVALID_RESERVATION_STATUS (결제 만료는 PENDING만 허용)")
  void notAllowedStatus_invalidStatus() {
    statusIs(ReservationStatus.CONFIRMED);

    assertErrorCode(
        () -> manager.expirePaymentTimeout(RESERVATION_ID),
        ReservationErrorCode.INVALID_RESERVATION_STATUS);
    verify(scheduleRepository, never()).tryDecreaseCapacity(anyLong(), anyInt());
  }

  private Reservation locked(long id, long scheduleId, int personCount) {
    Schedule schedule = mock(Schedule.class);
    given(schedule.getId()).willReturn(scheduleId);
    Reservation reservation = mock(Reservation.class);
    given(reservation.getId()).willReturn(id);
    given(reservation.getSchedule()).willReturn(schedule);
    given(reservation.getPersonCount()).willReturn(personCount);
    return reservation;
  }

  @Test
  @DisplayName("청크 만료: 좌석을 회차별로 합쳐 한 문장으로 먼저 돌려준 뒤, 잠근 예약을 한 문장으로 만료한다")
  void expireChunk_restoresSeatsPerScheduleInOneStatement() {
    List<Reservation> targets = List.of(locked(1L, 20L, 2), locked(2L, 10L, 1), locked(3L, 20L, 3));
    given(reservationRepository.expirePendingByIds(List.of(1L, 2L, 3L))).willReturn(3L);
    given(scheduleRepository.tryDecreaseCapacities(Map.of(10L, 1, 20L, 5))).willReturn(2);

    assertThat(manager.expirePaymentTimeoutChunk(targets)).isEqualTo(3);

    // 회차 행 배타 잠금(좌석 반환)이 예약 상태 변경보다 먼저 (외래 키 확인의 공유 잠금 → 배타 잠금 교착 방지)
    InOrder order = inOrder(scheduleRepository, reservationRepository);
    order.verify(scheduleRepository).tryDecreaseCapacities(Map.of(10L, 1, 20L, 5));
    order.verify(reservationRepository).expirePendingByIds(List.of(1L, 2L, 3L));
    verify(scheduleRepository, never()).tryDecreaseCapacity(anyLong(), anyInt());
    verify(seatReleaseOutbox).record(Map.of(10L, 1, 20L, 5));
  }

  @Test
  @DisplayName(
      "청크 만료: 잠근 예약 중 결제 대기가 아닌 예약이 있으면 INVALID_RESERVATION_STATUS를 던져 청크 전체(돌려준 좌석 포함)를 되돌리게 한다")
  void expireChunk_statusMismatch_throwsToRollBackChunk() {
    List<Reservation> targets = List.of(locked(1L, 10L, 1), locked(2L, 10L, 1));
    given(scheduleRepository.tryDecreaseCapacities(Map.of(10L, 2))).willReturn(1);
    given(reservationRepository.expirePendingByIds(List.of(1L, 2L))).willReturn(1L);

    assertErrorCode(
        () -> manager.expirePaymentTimeoutChunk(targets),
        ReservationErrorCode.INVALID_RESERVATION_STATUS);
  }

  @Test
  @DisplayName("청크 만료: 좌석을 돌려준 회차가 하나라도 모자라면 INVALID_CANCEL_COUNT를 던지고 예약 상태는 바꾸지 않는다")
  void expireChunk_restoreFails_invalidCancelCount() {
    List<Reservation> targets = List.of(locked(1L, 10L, 2), locked(2L, 20L, 1));
    given(scheduleRepository.tryDecreaseCapacities(Map.of(10L, 2, 20L, 1))).willReturn(1);

    assertErrorCode(
        () -> manager.expirePaymentTimeoutChunk(targets), ScheduleErrorCode.INVALID_CANCEL_COUNT);

    verifyNoInteractions(seatReleaseOutbox);
    verify(reservationRepository, never()).expirePendingByIds(any());
  }
}
