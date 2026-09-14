package com.popnup.popnupbackend.domain.reservation.dto.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ReservationCreateResponse {

  private final Long reservationId;
  private final String reservationNumber;

  public static ReservationCreateResponse from(Long reservationId, String reservationNumber) {
    return new ReservationCreateResponse(reservationId, reservationNumber);
  }
}
