package com.popnup.popnupbackend.domain.reservation.dto.response;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AdminReservationResponse {

  private final Long popupId;
  private final String popupTitle;

  private final Long reservationId;
  private final String reservationNumber;
  private final Integer personCount;
  private final ReservationStatus status;

  private final Long memberId;
  private final String memberName;

  private final LocalDate scheduledDate;
  private final LocalTime startTime;
  private final LocalTime endTime;

  public static AdminReservationResponse from(Reservation reservation) {
    return new AdminReservationResponse(
        reservation.getSchedule().getPopup().getId(),
        reservation.getSchedule().getPopup().getTitle(),
        reservation.getId(),
        reservation.getReservationNumber(),
        reservation.getPersonCount(),
        reservation.getStatus(),
        reservation.getMember().getId(),
        reservation.getMember().getName(),
        reservation.getSchedule().getScheduleDate(),
        reservation.getSchedule().getStartTime(),
        reservation.getSchedule().getEndTime());
  }
}
