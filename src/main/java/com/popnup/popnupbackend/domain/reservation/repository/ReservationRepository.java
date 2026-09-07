package com.popnup.popnupbackend.domain.reservation.repository;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository
    extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom {
  List<Reservation> findByStatusAndCreatedAtBefore(
      ReservationStatus status, LocalDateTime deadLine);
}
