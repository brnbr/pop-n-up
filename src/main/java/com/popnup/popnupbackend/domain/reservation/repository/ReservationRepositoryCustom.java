package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepositoryCustom {

  List<Reservation> findMemberReservations(Long memberId, int page, int size);

  boolean hasActiveReservation(Long scheduleId, Long memberId);

  List<Reservation> findAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status, int page, int size);

  Optional<Reservation> findByReservationNumberWithPessimisticLock(String reservationNumber);

  Optional<Reservation> findByIdWithPessimisticLock(Long id);

  int tryUpdateStatus(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses);

  Optional<ScheduleAndPersonCount> findScheduleAndPersonCount(Long reservationId);

  Optional<ReservationStatus> findStatusById(Long reservationId);

  record ScheduleAndPersonCount(Long scheduleId, Integer personCount) {}

  long expirePastConfirmedReservations(LocalDateTime startedBefore);

  List<Reservation> findPendingReservationsChunk(
      ReservationStatus status, LocalDateTime deadline, Long afterId, int chunkSize);
}
