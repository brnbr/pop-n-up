package com.popnup.popnupbackend.domain.reservation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ReservationCreateRequest {

  @NotNull(message = "예약 할 팝업을 선택해주세요")
  private Long scheduleId;

  @NotNull(message = "예약 인원을 입력해주세요")
  @Min(value = 1, message = "최소 1명 이상이어야 합니다.")
  private Integer personCount;
}
