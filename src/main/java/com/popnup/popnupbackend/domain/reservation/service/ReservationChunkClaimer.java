package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservationChunkClaimer {

  @Transactional
  public List<Reservation> claim(Supplier<List<Reservation>> lockedChunkSupplier) {
    return lockedChunkSupplier.get();
  }
}
