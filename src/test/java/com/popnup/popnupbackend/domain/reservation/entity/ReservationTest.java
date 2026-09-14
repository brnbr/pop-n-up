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

    // Schedule.addReservation()에서 popup 상태를 체크하지만,
    // ReservationTest는 addReservation()을 직접 호출하지 않고 Reservation 엔티티만 다루므로
    // popup은 Schedule 생성에 필요한 최소 필드만 채우면 됨
    Popup popup = createPopup(PopupStatus.OPEN);
    schedule =
        Schedule.createSchedule(
            popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);

    reservation = Reservation.createReservation(reservationNumber, member, schedule, personCount);
  }

  // Member는 IDENTITY 전략이라 DB 저장 전엔 id가 null이라 리플렉션으로 강제 주입
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
      log.info(
          "[createReservation.initialStatusIsPending] input(reservationNumber={}, personCount={})",
          reservationNumber,
          personCount);

      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[createReservation.initialStatusIsPending] expected={} actual={}",
          ReservationStatus.PENDING,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.PENDING);
    }
  }

  @Nested
  @DisplayName("confirm 검증")
  class Confirm {

    @Test
    @DisplayName("PENDING 상태이고 결제 성공이면 CONFIRMED로 전환된다")
    void successWhenPaymentSucceeded() {
      log.info(
          "[confirm.successWhenPaymentSucceeded] input(beforeStatus={}, paymentSucceeded=true)",
          reservation.getStatus());

      reservation.confirm(true);
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[confirm.successWhenPaymentSucceeded] expected={} actual={}",
          ReservationStatus.CONFIRMED,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("PENDING 상태이지만 결제 실패면 예외 발생 - PAYMENT_NOT_COMPLETED")
    void failWhenPaymentNotSucceeded() {
      log.info(
          "[confirm.failWhenPaymentNotSucceeded] input(beforeStatus={}, paymentSucceeded=false)",
          reservation.getStatus());

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservation.confirm(false));

      log.info(
          "[confirm.failWhenPaymentNotSucceeded] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.PAYMENT_NOT_COMPLETED,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.PAYMENT_NOT_COMPLETED);
    }

    @Test
    @DisplayName("PENDING이 아니면 결제 성공 여부와 무관하게 예외 발생")
    void failWhenNotPending() {
      reservation.confirm(true); // PENDING -> CONFIRMED로 미리 전환

      log.info(
          "[confirm.failWhenNotPending] input(currentStatus={}, paymentSucceeded=true)",
          reservation.getStatus());

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservation.confirm(true));

      log.info(
          "[confirm.failWhenNotPending] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.INVALID_RESERVATION_STATUS,
          exception.getErrorCode());

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

      log.info("[checkIn.success] input(beforeStatus={})", reservation.getStatus());

      reservation.checkIn();
      ReservationStatus actualStatus = reservation.getStatus();

      log.info("[checkIn.success] expected={} actual={}", ReservationStatus.USED, actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.USED);
    }

    @Test
    @DisplayName("이미 USED 상태면 예외 발생 (이중 체크인 방지 - 도메인 로직 검증)")
    void failWhenAlreadyUsed() {
      reservation.confirm(true);
      reservation.checkIn(); // 미리 USED로 전환

      log.info("[checkIn.failWhenAlreadyUsed] input(currentStatus={})", reservation.getStatus());

      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      log.info(
          "[checkIn.failWhenAlreadyUsed] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.ALREADY_PROCESSED_RESERVATION,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
    }

    @Test
    @DisplayName("PENDING 상태면 예외 발생 (결제 전 체크인 시도 방지)")
    void failWhenPending() {
      log.info("[checkIn.failWhenPending] input(currentStatus={})", reservation.getStatus());

      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      log.info(
          "[checkIn.failWhenPending] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.INVALID_RESERVATION_STATUS,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }

    @Test
    @DisplayName("CANCELED 상태면 예외 발생")
    void failWhenCanceled() {
      reservation.cancel();

      log.info("[checkIn.failWhenCanceled] input(currentStatus={})", reservation.getStatus());

      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      log.info(
          "[checkIn.failWhenCanceled] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.INVALID_RESERVATION_STATUS,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }

    @Test
    @DisplayName("EXPIRED 상태면 예외 발생")
    void failWhenExpired() {
      reservation.expired();

      log.info("[checkIn.failWhenExpired] input(currentStatus={})", reservation.getStatus());

      ServiceException exception = assertThrows(ServiceException.class, reservation::checkIn);

      log.info(
          "[checkIn.failWhenExpired] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.INVALID_RESERVATION_STATUS,
          exception.getErrorCode());

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
      log.info("[cancel.successFromPending] input(beforeStatus={})", reservation.getStatus());

      reservation.cancel();
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[cancel.successFromPending] expected={} actual={}",
          ReservationStatus.CANCELED,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("CONFIRMED 상태면 CANCELED로 전환된다")
    void successFromConfirmed() {
      reservation.confirm(true);

      log.info("[cancel.successFromConfirmed] input(beforeStatus={})", reservation.getStatus());

      reservation.cancel();
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[cancel.successFromConfirmed] expected={} actual={}",
          ReservationStatus.CANCELED,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 USED 상태면 예외 발생 (사용 완료 건은 취소 불가)")
    void failWhenAlreadyUsed() {
      reservation.confirm(true);
      reservation.checkIn();

      log.info("[cancel.failWhenAlreadyUsed] input(currentStatus={})", reservation.getStatus());

      ServiceException exception = assertThrows(ServiceException.class, reservation::cancel);

      log.info(
          "[cancel.failWhenAlreadyUsed] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.ALREADY_PROCESSED_RESERVATION,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
    }

    @Test
    @DisplayName("이미 CANCELED 상태면 예외 발생 - ALREADY_CANCELED_RESERVATION")
    void failWhenAlreadyCanceled() {
      reservation.cancel(); // 미리 CANCELED로 전환

      log.info("[cancel.failWhenAlreadyCanceled] input(currentStatus={})", reservation.getStatus());

      ServiceException exception = assertThrows(ServiceException.class, reservation::cancel);

      log.info(
          "[cancel.failWhenAlreadyCanceled] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.ALREADY_CANCELED_RESERVATION,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.ALREADY_CANCELED_RESERVATION);
    }

    @Test
    @DisplayName("EXPIRED 상태면 예외 발생 - INVALID_RESERVATION_STATUS")
    void failWhenExpired() {
      reservation.expired(); // PENDING -> EXPIRED

      log.info("[cancel.failWhenExpired] input(currentStatus={})", reservation.getStatus());

      ServiceException exception = assertThrows(ServiceException.class, reservation::cancel);

      log.info(
          "[cancel.failWhenExpired] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.INVALID_RESERVATION_STATUS,
          exception.getErrorCode());

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
      Long ownerId = 1L;

      log.info("[isOwnedBy.trueWhenSameMember] input(memberId={})", ownerId);

      boolean actual = reservation.isOwnedBy(ownerId);

      log.info("[isOwnedBy.trueWhenSameMember] expected=true actual={}", actual);

      assertThat(actual).isTrue();
    }

    @Test
    @DisplayName("다른 memberId면 false")
    void falseWhenDifferentMember() {
      Long otherId = 999L;

      log.info("[isOwnedBy.falseWhenDifferentMember] input(memberId={})", otherId);

      boolean actual = reservation.isOwnedBy(otherId);

      log.info("[isOwnedBy.falseWhenDifferentMember] expected=false actual={}", actual);

      assertThat(actual).isFalse();
    }

    @Test
    @DisplayName("memberId가 null이면 false")
    void falseWhenNull() {
      log.info("[isOwnedBy.falseWhenNull] input(memberId=null)");

      boolean actual = reservation.isOwnedBy(null);

      log.info("[isOwnedBy.falseWhenNull] expected=false actual={}", actual);

      assertThat(actual).isFalse();
    }
  }

  @Nested
  @DisplayName("expired 검증")
  class Expired {

    @Test
    @DisplayName("CONFIRMED 상태면 EXPIRED로 전환된다 (노쇼 만료)")
    void successFromConfirmed() {
      reservation.confirm(true);

      log.info("[expired.successFromConfirmed] input(beforeStatus={})", reservation.getStatus());

      reservation.expired();
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[expired.successFromConfirmed] expected={} actual={}",
          ReservationStatus.EXPIRED,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("PENDING 상태면 EXPIRED로 전환된다 (결제 타임아웃 만료)")
    void successFromPending() {
      log.info("[expired.successFromPending] input(beforeStatus={})", reservation.getStatus());

      reservation.expired();
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[expired.successFromPending] expected={} actual={}",
          ReservationStatus.EXPIRED,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("USED 상태면 아무 변화 없다 (예외도 없음 - 현재 도메인 로직 그대로 문서화)")
    void noChangeWhenUsed() {
      reservation.confirm(true);
      reservation.checkIn();

      log.info("[expired.noChangeWhenUsed] input(beforeStatus={})", reservation.getStatus());

      reservation.expired();
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[expired.noChangeWhenUsed] expected={} actual={}", ReservationStatus.USED, actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.USED);
    }

    @Test
    @DisplayName("CANCELED 상태면 아무 변화 없다 (예외도 없음 - 현재 도메인 로직 그대로 문서화)")
    void noChangeWhenCanceled() {
      reservation.cancel();

      log.info("[expired.noChangeWhenCanceled] input(beforeStatus={})", reservation.getStatus());

      reservation.expired();
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[expired.noChangeWhenCanceled] expected={} actual={}",
          ReservationStatus.CANCELED,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 EXPIRED면 멱등하게 그대로 유지된다")
    void idempotentWhenAlreadyExpired() {
      reservation.expired(); // PENDING -> EXPIRED

      log.info(
          "[expired.idempotentWhenAlreadyExpired] input(beforeStatus={})", reservation.getStatus());

      reservation.expired(); // 재호출
      ReservationStatus actualStatus = reservation.getStatus();

      log.info(
          "[expired.idempotentWhenAlreadyExpired] expected={} actual={}",
          ReservationStatus.EXPIRED,
          actualStatus);

      assertThat(actualStatus).isEqualTo(ReservationStatus.EXPIRED);
    }
  }
}
