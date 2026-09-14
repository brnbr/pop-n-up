package com.popnup.popnupbackend.domain.qrcode.dto.response;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class CheckInResponse {

  private final Long reservationId;
  private final String reservationNumber;
  private final String memberName;
  private final Integer personCount;

  public static CheckInResponse from(Reservation reservation) {
    return new CheckInResponse(
        reservation.getId(),
        reservation.getReservationNumber(),
        reservation.getMember().getName(),
        reservation.getPersonCount());
  }
}
