package com.popnup.popnupbackend.domain.payment.repository;

import com.popnup.popnupbackend.domain.payment.entity.Payment;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  Optional<Payment> findByReservation(Reservation reservation);

  Optional<Payment> findByReservationId(Long reservationId);

  long countByReservationId(Long reservationId);
}
