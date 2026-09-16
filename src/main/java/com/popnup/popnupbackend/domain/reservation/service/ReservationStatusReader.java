package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReservationStatusReader {

  private final ReservationRepository reservationRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<ReservationStatus> getFreshStatus(Long reservationId) {
    return reservationRepository.findStatusById(reservationId);
  }
}
