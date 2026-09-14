package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutProcessor {

  private final ReservationRepository reservationRepository;
  private final ReservationCancelManager reservationCancelManager;

  public void payTimeOut() {
    LocalDateTime deadLine = LocalDateTime.now().minusMinutes(10);
    List<Reservation> deadReservations =
        reservationRepository.findByStatusAndCreatedAtBefore(ReservationStatus.PENDING, deadLine);

    if (deadReservations.isEmpty()) {
      return;
    }

    log.info("[payTimeOut] 만료 대상: {}건", deadReservations.size());

    for (Reservation dr : deadReservations) {
      try {
        expireSingleTimeoutReservation(dr.getId());
      } catch (Exception e) {
        log.error("[payTimeOut] 예약 단건 만료 처리 실패 (ID: {})", dr.getId(), e);
      }
    }
  }

  // 만료 대상 단건 취소 및 좌석 복구
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void expireSingleTimeoutReservation(Long reservationId) {
    reservationCancelManager.expirePaymentTimeout(reservationId);
  }
}
