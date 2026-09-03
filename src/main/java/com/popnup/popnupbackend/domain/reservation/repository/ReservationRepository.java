package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository
    extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom {
  Optional<Reservation> findByIdAndMemberId(Long reservationId, Long memberId);
}
