package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutProcessor {

  private static final String PAYMENT_TIMEOUT_LABEL = "결제 미완료 만료";
  private static final String NO_SHOW_LABEL = "지난 회차 미방문 만료";

  private final ReservationRepository reservationRepository;
  private final ReservationCancelManager reservationCancelManager;
  private final MeterRegistry meterRegistry;

  @Value("${reservation.timeout.chunk-size:100}")
  private int chunkSize;

  @Value("${reservation.timeout.payment-timeout-seconds:600}")
  private long paymentTimeoutSeconds;

  public void expireUnpaidReservations() {
    LocalDateTime deadLine = LocalDateTime.now().minusSeconds(paymentTimeoutSeconds);
    int processed = 0;
    int skipped = 0;
    int failed = 0;
    int chunkNumber = 0;
    long afterId = 0L;

    log.debug("[{}] 청크 기반 처리 시작 (Chunk Size: {})", PAYMENT_TIMEOUT_LABEL, chunkSize);

    while (true) {
      long chunkStart = System.currentTimeMillis();
      List<Reservation> chunk =
          reservationRepository.findPendingReservationsChunk(
              ReservationStatus.PENDING, deadLine, afterId, chunkSize);
      if (chunk.isEmpty()) {
        break;
      }
      afterId = chunk.get(chunk.size() - 1).getId();
      chunkNumber++;

      for (Reservation target : chunk) {
        try {
          if (reservationCancelManager.expirePaymentTimeout(target.getId())) {
            processed++;
          } else {
            skipped++;
          }
        } catch (Exception e) {
          failed++;
          meterRegistry
              .counter("reservation.expire.failure", "label", PAYMENT_TIMEOUT_LABEL)
              .increment();
          log.error(
              "[{}] 예약 단건 처리 실패 - 다음 사이클에 재조회되어 재시도됩니다. reservationId={}",
              PAYMENT_TIMEOUT_LABEL,
              target.getId(),
              e);
        }
      }

      long chunkElapsedMs = System.currentTimeMillis() - chunkStart;
      log.debug(
          "[{}] 청크 #{} 처리 완료 - {}건, {}ms (건당 평균 {}ms)",
          PAYMENT_TIMEOUT_LABEL,
          chunkNumber,
          chunk.size(),
          chunkElapsedMs,
          String.format("%.2f", (double) chunkElapsedMs / chunk.size()));

      if (chunk.size() < chunkSize) {
        break;
      }
    }

    if (processed + skipped + failed > 0) {
      log.info(
          "[{}] 처리 완료 (성공 {}건, no-op {}건, 실패 {}건)",
          PAYMENT_TIMEOUT_LABEL,
          processed,
          skipped,
          failed);
    }
  }

  @Transactional
  public void expirePastReservations() {
    LocalDateTime startedBefore = LocalDateTime.now().minus(Schedule.CHECK_IN_GRACE);
    long expired = reservationRepository.expirePastConfirmedReservations(startedBefore);
    if (expired > 0) {
      log.info("[{}] 처리 완료 ({}건)", NO_SHOW_LABEL, expired);
    }
  }
}
