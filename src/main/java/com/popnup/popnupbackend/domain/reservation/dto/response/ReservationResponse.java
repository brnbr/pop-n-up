package com.popnup.popnupbackend.domain.reservation.dto.response;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ReservationResponse {

  private final Long reservationId;
  private final String reservationNumber;
  private final ReservationStatus status;
  private final LocalDateTime bookedDayTime;
  private final Integer personCound;

  public static ReservationResponse from(Reservation reservation) {
    return new ReservationResponse(
        reservation.getId(),
        reservation.getReservationNumber(),
        reservation.getStatus(),
        reservation.getCreatedAt(),
        reservation.getPersonCount());
  }
}
