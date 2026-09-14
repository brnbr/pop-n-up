package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ReservationCancelManagerTest {

  @Mock private ScheduleRepository scheduleRepository;
  @Mock private ReservationRepository reservationRepository;

  @InjectMocks private ReservationCancelManager reservationCancelManager;

  private Schedule schedule;
  private Reservation reservation;

  @BeforeEach
  void setUp() {
    Member member = Member.createLocal("test@test.com", "pw", "테스터");
    ReflectionTestUtils.setField(member, "id", 1L);

    Popup popup =
        Popup.builder()
            .title("테스트 팝업")
            .category(PopupCategory.ETC)
            .region("서울")
            .address("서울시 강남구")
            .startDate(LocalDate.now().minusDays(10))
            .endDate(LocalDate.now().plusDays(10))
            .isFree(true)
            .price(0)
            .status(PopupStatus.OPEN)
            .build();

    schedule =
        Schedule.createSchedule(
            popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
    ReflectionTestUtils.setField(schedule, "id", 100L);
    schedule.addReservation(2, LocalDateTime.now());

    reservation = Reservation.createReservation("R1", member, schedule, 2);
    ReflectionTestUtils.setField(reservation, "id", 10L);
  }

  @Nested
  @DisplayName("cancel 검증")
  class Cancel {

    @Test
    @DisplayName("PENDING 예약을 취소하면 CANCELED로 바뀌고 좌석이 복구된다")
    void successFromPending() {
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.of(schedule));

      log.info(
          "[cancel.successFromPending] input(reservationId=10, beforeCapacity={})",
          schedule.getNowCapacity());

      reservationCancelManager.cancel(10L);

      log.info(
          "[cancel.successFromPending] expectedStatus=CANCELED actualStatus={} expectedCapacity=0 actualCapacity={}",
          reservation.getStatus(),
          schedule.getNowCapacity());

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
      assertThat(schedule.getNowCapacity()).isZero();
    }

    @Test
    @DisplayName("이미 CANCELED면 멱등하게 무시하고 좌석도 건드리지 않는다")
    void idempotentWhenAlreadyCanceled() {
      reservation.cancel();
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));

      log.info(
          "[cancel.idempotentWhenAlreadyCanceled] input(reservationId=10, currentStatus=CANCELED)");

      reservationCancelManager.cancel(10L);

      log.info(
          "[cancel.idempotentWhenAlreadyCanceled] expectedCapacity(unchanged)=2 actualCapacity={}",
          schedule.getNowCapacity());

      verify(scheduleRepository, never()).findByIdWithPessimisticLock(100L);
      assertThat(schedule.getNowCapacity()).isEqualTo(2);
    }

    @Test
    @DisplayName("USED 상태면 예외가 발생하고 좌석도 복구되지 않는다")
    void failWhenUsed() {
      reservation.confirm(true);
      reservation.checkIn();
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));

      log.info("[cancel.failWhenUsed] input(reservationId=10, currentStatus=USED)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationCancelManager.cancel(10L));

      log.info(
          "[cancel.failWhenUsed] expectedErrorCode={} actualErrorCode={} capacityUnchanged={}",
          ReservationErrorCode.ALREADY_PROCESSED_RESERVATION,
          exception.getErrorCode(),
          schedule.getNowCapacity());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
      assertThat(schedule.getNowCapacity()).isEqualTo(2);

      verify(scheduleRepository, never()).findByIdWithPessimisticLock(100L);
    }

    @Test
    @DisplayName("예약이 존재하지 않으면 예외 발생")
    void notFound() {
      given(reservationRepository.findByIdWithPessimisticLock(999L)).willReturn(Optional.empty());

      log.info("[cancel.notFound] input(reservationId=999)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationCancelManager.cancel(999L));

      log.info(
          "[cancel.notFound] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.RESERVATION_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("스케줄이 존재하지 않으면 예외 발생")
    void scheduleNotFound() {
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.empty());

      log.info("[cancel.scheduleNotFound] input(reservationId=10, scheduleId=100)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationCancelManager.cancel(10L));

      log.info(
          "[cancel.scheduleNotFound] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.SCHEDULE_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("CONFIRMED 예약을 취소하면 CANCELED로 바뀌고 좌석이 복구된다")
    void successFromConfirmed() {
      reservation.confirm(true);
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.of(schedule));

      log.info(
          "[cancel.successFromConfirmed] input(reservationId=10, beforeCapacity={})",
          schedule.getNowCapacity());

      reservationCancelManager.cancel(10L);

      log.info(
          "[cancel.successFromConfirmed] expectedStatus=CANCELED actualStatus={} expectedCapacity=0 actualCapacity={}",
          reservation.getStatus(),
          schedule.getNowCapacity());

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
      assertThat(schedule.getNowCapacity()).isZero();
    }
  }

  @Nested
  @DisplayName("expire 검증")
  class Expire {

    @Test
    @DisplayName("CONFIRMED 예약을 만료하면(노쇼) EXPIRED로 바뀌고 좌석이 복구된다")
    void successFromConfirmed() {
      reservation.confirm(true);
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.of(schedule));

      log.info(
          "[expire.successFromConfirmed] input(reservationId=10, beforeCapacity={})",
          schedule.getNowCapacity());

      reservationCancelManager.expire(10L);

      log.info(
          "[expire.successFromConfirmed] expectedStatus=EXPIRED actualStatus={} expectedCapacity=0 actualCapacity={}",
          reservation.getStatus(),
          schedule.getNowCapacity());

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
      assertThat(schedule.getNowCapacity()).isZero();
    }

    @Test
    @DisplayName("PENDING 예약을 만료하면(결제 타임아웃) EXPIRED로 바뀌고 좌석이 복구된다")
    void successFromPending() {
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.of(schedule));

      log.info(
          "[expire.successFromPending] input(reservationId=10, beforeCapacity={})",
          schedule.getNowCapacity());

      reservationCancelManager.expire(10L);

      log.info(
          "[expire.successFromPending] expectedStatus=EXPIRED actualStatus={} expectedCapacity=0 actualCapacity={}",
          reservation.getStatus(),
          schedule.getNowCapacity());

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
      assertThat(schedule.getNowCapacity()).isZero();
    }

    @Test
    @DisplayName("이미 종료 상태(CANCELED)면 멱등하게 무시한다")
    void idempotentWhenAlreadyCanceled() {
      reservation.cancel();
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));

      log.info(
          "[expire.idempotentWhenAlreadyCanceled] input(reservationId=10, currentStatus=CANCELED)");

      reservationCancelManager.expire(10L);

      log.info(
          "[expire.idempotentWhenAlreadyCanceled] expectedStatus(unchanged)=CANCELED actualStatus={}",
          reservation.getStatus());

      verify(scheduleRepository, never()).findByIdWithPessimisticLock(100L);
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 EXPIRED면 멱등하게 무시한다")
    void idempotentWhenAlreadyExpired() {
      reservation.expired(); // PENDING -> EXPIRED (락 없이 직접 상태 전이)
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));

      log.info(
          "[expire.idempotentWhenAlreadyExpired] input(reservationId=10, currentStatus=EXPIRED)");

      reservationCancelManager.expire(10L);

      log.info(
          "[expire.idempotentWhenAlreadyExpired] expectedStatus(unchanged)=EXPIRED actualStatus={}",
          reservation.getStatus());

      verify(scheduleRepository, never()).findByIdWithPessimisticLock(100L);
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }
  }
}
