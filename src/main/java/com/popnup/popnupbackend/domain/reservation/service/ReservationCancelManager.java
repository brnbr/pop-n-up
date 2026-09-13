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
    processTerminalStatusChange(reservationId, TerminalAction.CANCEL);
  }

  @Transactional
  public void expire(Long reservationId) {
    processTerminalStatusChange(reservationId, TerminalAction.EXPIRE);
  }

  private void processTerminalStatusChange(Long reservationId, TerminalAction action) {
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

    if (action == TerminalAction.CANCEL) {
      reservation.cancel();
    } else {
      reservation.expired();
    }

    if (schedule != null) {
      schedule.cancelReservation(reservation.getPersonCount());
    }
  }

  private enum TerminalAction {
    CANCEL,
    EXPIRE
  }
}
