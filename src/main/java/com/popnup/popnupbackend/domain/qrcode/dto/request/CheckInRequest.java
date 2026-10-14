package com.popnup.popnupbackend.domain.qrcode.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CheckInRequest {

  @NotBlank(message = "예약 번호를 입력하세요")
  private String reservationNumber;

  public CheckInRequest(String reservationNumber) {
    this.reservationNumber = reservationNumber;
  }
}
