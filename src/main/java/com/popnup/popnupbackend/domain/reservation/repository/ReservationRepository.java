package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository
    extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom {

  List<Reservation> findByStatusAndCreatedAtBefore(ReservationStatus status, LocalDateTime deadLine);

  Optional<Reservation> findByIdAndMemberId(Long reservationId, Long memberId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
  @Query("SELECT r FROM Reservation r WHERE r.id = :id")
  Optional<Reservation> findByIdWithPessimisticLock(@Param("id") Long id);
}
