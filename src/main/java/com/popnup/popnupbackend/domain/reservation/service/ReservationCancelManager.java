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
    Reservation reservation = reservationRepository.findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (reservation.getStatus() == ReservationStatus.CANCELED) {
      return;
    }

    Long scheduleId = reservation.getSchedule().getId();
    Schedule schedule = scheduleRepository.findByIdWithPessimisticLock(scheduleId).orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    reservation.cancel();
    schedule.cancelReservation(reservation.getPersonCount());
  }
}
