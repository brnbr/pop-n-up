package com.popnup.popnupbackend.domain.reservation.enums;

public enum ReservationStatus {
  PENDING,
  CONFIRMED,
  CANCELED,
  USED,
  EXPIRED;

  public boolean canConfirm() {
    return this == PENDING;
  }

  public boolean canCheckIn() {
    return this == CONFIRMED;
  }

  public boolean isEnded() {
    return this == CANCELED || this == EXPIRED;
  }
}
