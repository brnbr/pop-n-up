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

  // 팝업 정보
  private final Long popupId;
  private final String popupTitle;

  // 예약 정보
  private final Long reservationid;
  private final String reservationNumber;
  private final Integer personcount;
  private final ReservationStatus status;

  // 예약자 정보
  private final Long memberId;
  private final String memberName;

  // 일자별 정보
  private final LocalDate scheduledDate; // 특정 일자 명단
  private final LocalTime startTime; // 이용 시간대 구분용
  private final LocalTime endTime; // 이용 시간대 구분용

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
