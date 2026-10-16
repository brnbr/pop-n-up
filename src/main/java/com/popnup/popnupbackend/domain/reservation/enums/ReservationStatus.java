package com.popnup.popnupbackend.domain.reservation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReservationStatus {
  PENDING("예약 대기"),
  CONFIRMED("예약 확정"),
  CANCELED("예약 취소"),
  USED("사용됨"),
  EXPIRED("만료됨");

  private final String description;

  /** 결제 완료로 예약을 확정할 수 있는 상태 */
  public boolean canConfirm() {
    return this == PENDING;
  }

  /** 체크인할 수 있는 상태 */
  public boolean canCheckIn() {
    return this == CONFIRMED;
  }

  /** 취소나 만료로 이미 끝난 상태 */
  public boolean isEnded() {
    return this == CANCELED || this == EXPIRED;
  }
}
