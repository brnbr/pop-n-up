package com.popnup.popnupbackend.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.error.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationTest {

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
  @DisplayName("새 예약은 PENDING으로 시작한다")
  void createReservation_startsPending() {
    Reservation reservation = reservationIn(ReservationStatus.PENDING);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
  }

  @Test
  @DisplayName("confirm: PENDING이고 결제가 끝났으면 CONFIRMED가 된다")
  void confirm_pendingAndPaid() {
    Reservation reservation = reservationIn(ReservationStatus.PENDING);

    reservation.confirm(true);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
  }

  @Test
  @DisplayName("confirm: PENDING이어도 결제가 안 끝났으면 PAYMENT_NOT_COMPLETED이고 상태는 그대로다")
  void confirm_pendingButNotPaid() {
    Reservation reservation = reservationIn(ReservationStatus.PENDING);

    assertErrorCode(() -> reservation.confirm(false), ReservationErrorCode.PAYMENT_NOT_COMPLETED);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"CONFIRMED", "CANCELED", "USED", "EXPIRED"})
  @DisplayName("confirm: PENDING이 아니면 결제 여부와 관계없이 INVALID_RESERVATION_STATUS이고 상태는 그대로다")
  void confirm_notPending_invalid(ReservationStatus status) {
    Reservation paid = reservationIn(status);
    Reservation unpaid = reservationIn(status);

    assertErrorCode(() -> paid.confirm(true), ReservationErrorCode.INVALID_RESERVATION_STATUS);
    assertErrorCode(() -> unpaid.confirm(false), ReservationErrorCode.INVALID_RESERVATION_STATUS);

    assertThat(paid.getStatus()).isEqualTo(status);
    assertThat(unpaid.getStatus()).isEqualTo(status);
  }

  @Test
  @DisplayName("checkIn: CONFIRMED면 USED가 된다")
  void checkIn_confirmed() {
    Reservation reservation = reservationIn(ReservationStatus.CONFIRMED);

    reservation.checkIn();

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
  }

  @Test
  @DisplayName("checkIn: 이미 USED면 ALREADY_PROCESSED_RESERVATION")
  void checkIn_alreadyUsed() {
    Reservation reservation = reservationIn(ReservationStatus.USED);

    assertErrorCode(reservation::checkIn, ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"PENDING", "CANCELED", "EXPIRED"})
  @DisplayName("checkIn: CONFIRMED/USED가 아니면 INVALID_RESERVATION_STATUS이고 상태는 그대로다")
  void checkIn_otherStatus_invalid(ReservationStatus status) {
    Reservation reservation = reservationIn(status);

    assertErrorCode(reservation::checkIn, ReservationErrorCode.INVALID_RESERVATION_STATUS);

    assertThat(reservation.getStatus()).isEqualTo(status);
  }
}
