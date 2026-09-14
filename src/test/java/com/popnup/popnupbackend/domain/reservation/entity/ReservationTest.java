package com.popnup.popnupbackend.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Slf4j
class ReservationTest {

  private Member member;
  private Schedule schedule;
  private Reservation reservation;

  private final String reservationNumber = "R20260912TEST0001";
  private final int personCount = 2;

  @BeforeEach
  void setUp() {
    member = createMemberWithId(1L);

    Popup popup = createPopup(PopupStatus.OPEN);

    schedule =
        Schedule.createSchedule(
            popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);

    reservation = Reservation.createReservation(reservationNumber, member, schedule, personCount);
  }

  private Member createMemberWithId(Long id) {
    Member m = Member.createLocal("test@test.com", "password", "테스터");

    ReflectionTestUtils.setField(m, "id", id);

    return m;
  }

  private Popup createPopup(PopupStatus status) {
    return Popup.builder()
        .title("테스트 팝업")
        .category(PopupCategory.ETC)
        .region("서울")
        .address("서울시 강남구")
        .startDate(LocalDate.now().minusDays(10))
        .endDate(LocalDate.now().plusDays(10))
        .isFree(true)
        .price(0)
        .status(status)
        .build();
  }

  @Nested
  @DisplayName("createReservation 생성 검증")
  class CreateReservation {

    @Test
    @DisplayName("생성 직후 상태는 PENDING이다")
    void initialStatusIsPending() {
      ReservationStatus actualStatus = reservation.getStatus();

      assertThat(actualStatus).isEqualTo(ReservationStatus.PENDING);
    }
  }

  @Nested
  @DisplayName("confirm 검증")
  class Confirm {

    @Test
    @DisplayName("PENDING 상태이고 결제 성공이면 CONFIRMED로 전환된다")
    void successWhenPaymentSucceeded() {
      reservation.confirm(true);

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("PENDING 상태이지만 결제 실패면 예외 발생")
    void failWhenPaymentNotSucceeded() {
      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservation.confirm(false));

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.PAYMENT_NOT_COMPLETED);
    }

    @Test
    @DisplayName("PENDING이 아니면 결제 성공 여부와 무관하게 예외 발생")
    void failWhenNotPending() {
      reservation.confirm(true);

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservation.confirm(true));

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }
  }

  @Nested
  @DisplayName("checkIn 검증")
  class CheckIn {

    @Test
    @DisplayName("CONFIRMED 상태면 USED로 전환된다")
    void success() {
      reservation.confirm(true);

      reservation.checkIn();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
    }

    @Test
    @DisplayName("이미 USED 상태면 예외 발생")
    void failWhenAlreadyUsed() {
      reservation.confirm(true);
      reservation.checkIn();

      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
    }

    @Test
    @DisplayName("PENDING 상태면 예외 발생")
    void failWhenPending() {
      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }

    @Test
    @DisplayName("CANCELED 상태면 예외 발생")
    void failWhenCanceled() {
      reservation.cancel();

      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }

    @Test
    @DisplayName("EXPIRED 상태면 예외 발생")
    void failWhenExpired() {
      reservation.expirePaymentTimeout();

      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }
  }

  @Nested
  @DisplayName("cancel 검증")
  class Cancel {

    @Test
    @DisplayName("PENDING 상태면 CANCELED로 전환된다")
    void successFromPending() {
      reservation.cancel();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("CONFIRMED 상태면 CANCELED로 전환된다")
    void successFromConfirmed() {
      reservation.confirm(true);

      reservation.cancel();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 USED 상태면 예외 발생")
    void failWhenAlreadyUsed() {
      reservation.confirm(true);
      reservation.checkIn();

      ServiceException exception = assertThrows(ServiceException.class, reservation::cancel);

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
    }

    @Test
    @DisplayName("이미 CANCELED 상태면 예외 발생")
    void failWhenAlreadyCanceled() {
      reservation.cancel();

      ServiceException exception = assertThrows(ServiceException.class, reservation::cancel);

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.ALREADY_CANCELED_RESERVATION);
    }

    @Test
    @DisplayName("EXPIRED 상태면 예외 발생")
    void failWhenExpired() {
      reservation.expirePaymentTimeout();

      ServiceException exception = assertThrows(ServiceException.class, reservation::cancel);

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }
  }

  @Nested
  @DisplayName("isOwnedBy 검증")
  class IsOwnedBy {

    @Test
    @DisplayName("동일한 memberId면 true")
    void trueWhenSameMember() {
      assertThat(reservation.isOwnedBy(1L)).isTrue();
    }

    @Test
    @DisplayName("다른 memberId면 false")
    void falseWhenDifferentMember() {
      assertThat(reservation.isOwnedBy(999L)).isFalse();
    }

    @Test
    @DisplayName("memberId가 null이면 false")
    void falseWhenNull() {
      assertThat(reservation.isOwnedBy(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("결제 타임아웃 만료 검증")
  class ExpirePaymentTimeout {

    @Test
    @DisplayName("PENDING 상태면 EXPIRED로 전환된다")
    void successFromPending() {
      reservation.expirePaymentTimeout();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("CONFIRMED 상태면 변경되지 않는다")
    void noChangeWhenConfirmed() {
      reservation.confirm(true);

      reservation.expirePaymentTimeout();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("USED 상태면 변경되지 않는다")
    void noChangeWhenUsed() {
      reservation.confirm(true);
      reservation.checkIn();

      reservation.expirePaymentTimeout();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
    }

    @Test
    @DisplayName("CANCELED 상태면 변경되지 않는다")
    void noChangeWhenCanceled() {
      reservation.cancel();

      reservation.expirePaymentTimeout();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 EXPIRED면 그대로 유지된다")
    void idempotentWhenAlreadyExpired() {
      reservation.expirePaymentTimeout();

      reservation.expirePaymentTimeout();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }
  }

  @Nested
  @DisplayName("노쇼 만료 검증")
  class ExpireNoShow {

    @Test
    @DisplayName("CONFIRMED 상태면 EXPIRED로 전환된다")
    void successFromConfirmed() {
      reservation.confirm(true);

      reservation.expireNoShow();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("PENDING 상태면 변경되지 않는다")
    void noChangeWhenPending() {
      reservation.expireNoShow();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("USED 상태면 변경되지 않는다")
    void noChangeWhenUsed() {
      reservation.confirm(true);
      reservation.checkIn();

      reservation.expireNoShow();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
    }

    @Test
    @DisplayName("CANCELED 상태면 변경되지 않는다")
    void noChangeWhenCanceled() {
      reservation.cancel();

      reservation.expireNoShow();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 EXPIRED 상태면 그대로 유지된다")
    void noChangeWhenExpired() {
      reservation.expirePaymentTimeout();

      reservation.expireNoShow();

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }
  }
}
