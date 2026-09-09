package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepositoryCustom {
  // 사용자 - 명단 조회
  List<Reservation> getAllReservation(Long memberId);

  // 예약 유효성 검사
  boolean hasActiveReservation(Long scheduleId, Long memberId);

  // 관리자 - 명단 조회
  List<Reservation> findAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status);

  // 체크인 단 건 조회
  Optional<Reservation> findByReservationNumber(String reservationNumber);

  // 만료 예약 찾기
  List<Reservation> findExpiredReservations(LocalDate today, LocalTime currentTime);
}
