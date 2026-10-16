package com.popnup.popnupbackend.domain.qrcode.service;

import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.support.KstTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckInService {

  private final ReservationRepository reservationRepository;

  @Transactional
  public CheckInResponse checkIn(CheckInRequest request) {
    Reservation reservation =
        reservationRepository
            .findByReservationNumberWithPessimisticLock(request.getReservationNumber())
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (reservation.getStatus().canCheckIn()
        && !reservation.getSchedule().isCheckInAvailable(KstTime.now())) {
      throw ReservationErrorCode.CHECK_IN_NOT_AVAILABLE_TIME.toException();
    }

    reservation.checkIn();
    return CheckInResponse.from(reservation);
  }
}
