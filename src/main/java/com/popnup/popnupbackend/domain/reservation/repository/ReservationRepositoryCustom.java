package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import java.util.List;

public interface ReservationRepositoryCustom {
  List<Reservation> getAllReservation(Long memberId);

  boolean hasActiveReservation(Long scheduleId, Long memberId);
}
