package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
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

  // 스케줄 락 획득 -> 상태 변경 -> 인원 복구
  @Transactional
  public void cancel(Reservation reservation) {
    if (reservation.getStatus() == ReservationStatus.CANCELED) {
      return;
    }

    Long scheduleId = reservation.getSchedule().getId();
    Schedule schedule =
        scheduleRepository
            .findByIdWithPessimisticLock(scheduleId)
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    reservation.cancel();
    schedule.cancelReservation(reservation.getPersonCount());
  }
}
