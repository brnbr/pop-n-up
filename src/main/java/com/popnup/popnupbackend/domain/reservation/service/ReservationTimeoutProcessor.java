package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutProcessor {

    private final ReservationRepository reservationRepository;
    private final ScheduleRepository scheduleRepository;


     //만료 대상 단건 취소 및 좌석 복구
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelSingleTimeoutReservation(Long reservationId) {
        Reservation reservation =
                reservationRepository
                        .findById(reservationId)
                        .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

        reservation.cancel();

        Long scheduleId = reservation.getSchedule().getId();
        Schedule schedule =
                scheduleRepository
                        .findByIdWithPessimisticLock(scheduleId)
                        .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

        schedule.cancelReservation(reservation.getPersonCount());
    }
}