package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationRepositoryCustom {

  List<Reservation> findMemberReservations(Long memberId, int page, int size);

  List<Reservation> findMemberReservationsAfter(Long memberId, MemberCursor after, int limit);

  boolean hasActiveReservation(Long scheduleId, Long memberId);

  List<Reservation> findAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status, int page, int size);

  List<Reservation> findAdminReservationsAfter(
      Long popupId, LocalDate scheduleDate, ReservationStatus status, AdminCursor after, int limit);

  record AdminCursor(LocalDate scheduleDate, LocalTime startTime, Long id) {}

  record MemberCursor(LocalDateTime createdAt, Long id) {}

  Optional<Reservation> findByReservationNumberWithPessimisticLock(String reservationNumber);

  Optional<Reservation> findByIdWithPessimisticLock(Long id);

  int tryUpdateStatus(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses);

  long expirePastConfirmedReservations(LocalDateTime startedBefore);

  List<Reservation> claimExpiredPendingChunk(
      LocalDateTime deadline, Collection<Long> excludedIds, int chunkSize);

  long expirePendingByIds(Collection<Long> reservationIds);
}
