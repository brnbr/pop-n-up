package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReservationCancelManager {

  private final ScheduleRepository scheduleRepository;
  private final ReservationRepository reservationRepository;

  @Transactional
  public void cancel(Long reservationId) {
    processCancel(reservationId);
  }

  @Transactional
  public void expirePaymentTimeout(Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (reservation.getStatus() != ReservationStatus.PENDING) {
      return;
    }

    Schedule schedule =
        scheduleRepository
            .findByIdWithPessimisticLock(reservation.getSchedule().getId())
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    reservation.expirePaymentTimeout();
    schedule.cancelReservation(reservation.getPersonCount());
  }

  @Transactional
  public void expireNoShow(Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
      return;
    }

    Schedule schedule =
        scheduleRepository
            .findByIdWithPessimisticLock(reservation.getSchedule().getId())
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    reservation.expireNoShow();
    schedule.cancelReservation(reservation.getPersonCount());
  }

  private void processCancel(Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    ReservationStatus currentStatus = reservation.getStatus();

    if (currentStatus == ReservationStatus.CANCELED || currentStatus == ReservationStatus.EXPIRED) {
      return;
    }

    boolean needsCapacityRestore =
        currentStatus == ReservationStatus.PENDING || currentStatus == ReservationStatus.CONFIRMED;

    Schedule schedule = null;

    if (needsCapacityRestore) {
      Long scheduleId = reservation.getSchedule().getId();

      schedule =
          scheduleRepository
              .findByIdWithPessimisticLock(scheduleId)
              .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);
    }

    reservation.cancel();

    if (schedule != null) {
      schedule.cancelReservation(reservation.getPersonCount());
    }
  }
}
