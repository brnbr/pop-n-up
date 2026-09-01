package com.popnup.popnupbackend.domain.reservation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReservationStatus {
    PENDING("예약 대기"),
    CONFIRMED("예약 확정"),
    CANCELED("예약 취소");

    private final String description;
}
