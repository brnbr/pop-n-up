package com.popnup.popnupbackend.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2031, 5, 10, 10, 0);

  private Reservation reservationIn(ReservationStatus status) {
    Reservation reservation =
        Reservation.createReservation("R20260915TEST", mock(Member.class), mock(Schedule.class), 2);
    ReflectionTestUtils.setField(reservation, "status", status);
    return reservation;
  }

  private void assertErrorCode(Runnable action, Object expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ServiceException.class)
        .satisfies(e -> assertThat(((ServiceException) e).getErrorCode()).isEqualTo(expected));
  }

  @Test
  @DisplayName("confirm: PENDING이면 CONFIRMED가 된다")
  void confirm_pending() {
    Reservation reservation = reservationIn(ReservationStatus.PENDING);

    reservation.confirm();

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"CONFIRMED", "CANCELED", "USED", "EXPIRED"})
  @DisplayName("confirm: PENDING이 아니면 INVALID_RESERVATION_STATUS이고 상태는 그대로다")
  void confirm_notPending_invalid(ReservationStatus status) {
    Reservation reservation = reservationIn(status);

    assertErrorCode(reservation::confirm, ReservationErrorCode.INVALID_RESERVATION_STATUS);

    assertThat(reservation.getStatus()).isEqualTo(status);
  }

  @Test
  @DisplayName("checkIn: CONFIRMED이고 체크인 가능한 시간이면 USED가 된다")
  void checkIn_confirmed() {
    Reservation reservation = reservationIn(ReservationStatus.CONFIRMED);
    given(reservation.getSchedule().isCheckInAvailable(NOW)).willReturn(true);

    reservation.checkIn(NOW);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
  }

  @Test
  @DisplayName(
      "checkIn: CONFIRMED여도 체크인 가능한 시간이 아니면(어제 QR, 종료된 회차) CHECK_IN_NOT_AVAILABLE_TIME이고 상태는 그대로다")
  void checkIn_outsideWindow_rejected() {
    Reservation reservation = reservationIn(ReservationStatus.CONFIRMED);
    given(reservation.getSchedule().isCheckInAvailable(NOW)).willReturn(false);

    assertErrorCode(
        () -> reservation.checkIn(NOW), ReservationErrorCode.CHECK_IN_NOT_AVAILABLE_TIME);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
  }

  @Test
  @DisplayName("checkIn: 이미 USED면 시간 검사보다 먼저 ALREADY_PROCESSED_RESERVATION")
  void checkIn_alreadyUsed() {
    Reservation reservation = reservationIn(ReservationStatus.USED);

    assertErrorCode(
        () -> reservation.checkIn(NOW), ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
    verify(reservation.getSchedule(), never()).isCheckInAvailable(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"PENDING", "CANCELED", "EXPIRED"})
  @DisplayName("checkIn: CONFIRMED/USED가 아니면 INVALID_RESERVATION_STATUS이고 상태는 그대로다")
  void checkIn_otherStatus_invalid(ReservationStatus status) {
    Reservation reservation = reservationIn(status);

    assertErrorCode(
        () -> reservation.checkIn(NOW), ReservationErrorCode.INVALID_RESERVATION_STATUS);

    assertThat(reservation.getStatus()).isEqualTo(status);
  }

  @Test
  @DisplayName("validatePayable: PENDING이면 결제를 진행할 수 있고 상태는 그대로다")
  void validatePayable_pending() {
    Reservation reservation = reservationIn(ReservationStatus.PENDING);

    reservation.validatePayable();

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"CONFIRMED", "CANCELED", "USED", "EXPIRED"})
  @DisplayName("validatePayable: PENDING이 아니면 INVALID_RESERVATION_STATUS")
  void validatePayable_notPending_invalid(ReservationStatus status) {
    Reservation reservation = reservationIn(status);

    assertErrorCode(reservation::validatePayable, ReservationErrorCode.INVALID_RESERVATION_STATUS);
  }

  @Test
  @DisplayName(
      "validateOwnedBy: 예약한 회원이면 통과하고, 다른 회원이거나 회원 정보가 없으면 UNAUTHORIZED_RESERVATION_ACCESS")
  void validateOwnedBy() {
    Reservation reservation = reservationIn(ReservationStatus.CONFIRMED);
    given(reservation.getMember().getId()).willReturn(1L);

    reservation.validateOwnedBy(1L);

    assertErrorCode(
        () -> reservation.validateOwnedBy(2L),
        ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
    assertErrorCode(
        () -> reservation.validateOwnedBy(null),
        ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
  }

  @Test
  @DisplayName("validateCancelable(R3): 이미 시작된 회차의 결제 완료 예약은 CANCEL_NOT_ALLOWED_AFTER_START")
  void validateCancelable_confirmedAfterStart_rejected() {
    Reservation reservation = reservationIn(ReservationStatus.CONFIRMED);
    given(reservation.getSchedule().isAlreadyStarted(NOW)).willReturn(true);

    assertErrorCode(
        () -> reservation.validateCancelable(NOW),
        ReservationErrorCode.CANCEL_NOT_ALLOWED_AFTER_START);
  }

  @Test
  @DisplayName("validateCancelable(R3): 시작 전 회차의 결제 완료 예약은 취소할 수 있다")
  void validateCancelable_confirmedBeforeStart_allowed() {
    Reservation reservation = reservationIn(ReservationStatus.CONFIRMED);
    given(reservation.getSchedule().isAlreadyStarted(NOW)).willReturn(false);

    reservation.validateCancelable(NOW);
  }

  @Test
  @DisplayName("validateCancelable(R3): 결제 대기(PENDING)는 회차 시작 여부와 관계없이 취소할 수 있다 (좌석 반환)")
  void validateCancelable_pending_allowedWithoutCheckingStart() {
    Reservation reservation = reservationIn(ReservationStatus.PENDING);

    reservation.validateCancelable(NOW);

    verify(reservation.getSchedule(), never()).isAlreadyStarted(any());
  }

  @Test
  @DisplayName("validateQrIssuable: CONFIRMED면 QR을 발급할 수 있다")
  void validateQrIssuable_confirmed() {
    reservationIn(ReservationStatus.CONFIRMED).validateQrIssuable();
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"PENDING", "CANCELED", "USED", "EXPIRED"})
  @DisplayName("validateQrIssuable: CONFIRMED가 아니면 INVALID_RESERVATION_STATUS")
  void validateQrIssuable_notConfirmed_invalid(ReservationStatus status) {
    Reservation reservation = reservationIn(status);

    assertErrorCode(
        reservation::validateQrIssuable, ReservationErrorCode.INVALID_RESERVATION_STATUS);
  }
}
