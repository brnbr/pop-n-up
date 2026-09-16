package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepositoryCustom {

  List<Reservation> getAllReservation(Long memberId);

  boolean hasActiveReservation(Long scheduleId, Long memberId);

  List<Reservation> findAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status);

  Optional<Reservation> findByReservationNumberWithPessimisticLock(String reservationNumber);

  Optional<Reservation> findByIdWithPessimisticLock(Long id);

  int tryUpdateStatus(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses);

  Optional<ScheduleAndPersonCount> findScheduleAndPersonCount(Long reservationId);

  Optional<ReservationStatus> findStatusById(Long reservationId);

  record ScheduleAndPersonCount(Long scheduleId, Integer personCount) {}

  List<Reservation> findExpiredReservationsChunk(
      LocalDate today, LocalTime currentTime, int chunkSize);

  List<Reservation> findPendingReservationsChunk(
      ReservationStatus status, LocalDateTime deadline, int chunkSize);

  List<Reservation> findPendingReservationsChunkForUpdate(
      ReservationStatus status, LocalDateTime deadline, int chunkSize);

  List<Reservation> findExpiredReservationsChunkForUpdate(
      LocalDate today, LocalTime currentTime, int chunkSize);
}
