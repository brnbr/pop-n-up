package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

  private static final int CHUNK_SIZE = 100;

  public void payTimeOut() {
    LocalDateTime deadLine = LocalDateTime.now().minusMinutes(10);
    processInChunks(
        "결제 미완료 만료",
        () ->
            reservationRepository.findPendingReservationsChunk(
                ReservationStatus.PENDING, deadLine, CHUNK_SIZE),
        reservationId -> reservationCancelManager.expirePaymentTimeout(reservationId));
  }

  public void expirePastReservations() {
    LocalDate today = LocalDate.now();
    LocalTime nowTime = LocalTime.now();
    processInChunks(
        "지난 회차 미방문 만료",
        () -> reservationRepository.findExpiredReservationsChunk(today, nowTime, CHUNK_SIZE),
        reservationId -> reservationCancelManager.expireNoShow(reservationId));
  }

  private void processInChunks(
      String label, Supplier<List<Reservation>> chunkSupplier, Consumer<Long> expireAction) {
    int totalProcessed = 0;
    log.info("[{}] 청크 기반 처리 시작 (Chunk Size: {})", label, CHUNK_SIZE);

    while (true) {
      List<Reservation> chunk = chunkSupplier.get();

      if (chunk.isEmpty()) {
        break;
      }

      for (Reservation target : chunk) {
        try {
          expireSingleReservation(target.getId(), expireAction);
          totalProcessed++;
        } catch (Exception e) {
          log.error("[{}] 예약 단건 처리 실패 (ID: {})", label, target.getId(), e);
        }
      }

      if (chunk.size() < CHUNK_SIZE) {
        break;
      }
    }

    if (totalProcessed > 0) {
      log.info("[{}] 처리 완료 (총 처리 건수: {}건)", label, totalProcessed);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void expireSingleReservation(Long reservationId, Consumer<Long> expireAction) {
    expireAction.accept(reservationId);
  }
}
