package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutProcessor {

  private final ReservationRepository reservationRepository;
  private final ReservationCancelManager reservationCancelManager;
  private final MeterRegistry meterRegistry;

  // 운영 기본값 100. C-3 실험에서는 docker-compose 환경변수 RESERVATION_TIMEOUT_CHUNK_SIZE로
  // 재빌드 없이 값을 바꿔가며 측정할 수 있다.
  @Value("${reservation.timeout.chunk-size:100}")
  private int chunkSize;

  // 운영 기본값은 10분(600초)이지만, 부하 테스트에서는 매번 10분을 기다릴 수 없으므로
  // application-loadtest.yml 등에서 짧게(예: 15초) 오버라이드해서 사용한다.
  @Value("${reservation.timeout.payment-timeout-seconds:600}")
  private long paymentTimeoutSeconds;

  public void payTimeOut() {
    LocalDateTime deadLine = LocalDateTime.now().minusSeconds(paymentTimeoutSeconds);
    processInChunks(
        "결제 미완료 만료",
        () ->
            reservationRepository.findPendingReservationsChunk(
                ReservationStatus.PENDING, deadLine, chunkSize),
        reservationId -> reservationCancelManager.expirePaymentTimeout(reservationId));
  }

  public void expirePastReservations() {
    LocalDate today = LocalDate.now();
    LocalTime nowTime = LocalTime.now();
    processInChunks(
        "지난 회차 미방문 만료",
        () -> reservationRepository.findExpiredReservationsChunk(today, nowTime, chunkSize),
        reservationId -> reservationCancelManager.expireNoShow(reservationId));
  }

  private void processInChunks(
      String label, Supplier<List<Reservation>> chunkSupplier, Consumer<Long> expireAction) {
    int totalProcessed = 0;
    int totalFailed = 0;
    int chunkNumber = 0;
    log.info("[{}] 청크 기반 처리 시작 (Chunk Size: {})", label, chunkSize);

    while (true) {
      long chunkStart = System.currentTimeMillis();
      List<Reservation> chunk = chunkSupplier.get();

      if (chunk.isEmpty()) {
        break;
      }

      chunkNumber++;
      for (Reservation target : chunk) {
        try {
          expireSingleReservation(target.getId(), expireAction);
          totalProcessed++;
        } catch (Exception e) {
          totalFailed++;
          // 실패한 예약은 상태가 그대로 남아있어 다음 조회 청크에도 그대로 다시 걸린다.
          // 원인이 매 사이클 동일하게 재현되는 케이스(예: 좌석 카운트 드리프트)라면
          // 여기서 계속 실패 로그만 쌓이고 아무도 알아채지 못한 채 영구히 만료 처리가
          // 안 되는 상태가 될 수 있어, 카운터로 관측 가능하게 만든다.
          // reservationId는 카디널리티가 높아 메트릭 태그로 넣지 않고 로그로만 남긴다.
          meterRegistry.counter("reservation.expire.failure", "label", label).increment();
          log.error(
              "[{}] 예약 단건 처리 실패 - 다음 사이클에 재조회되어 재시도됩니다. reservationId={}",
              label,
              target.getId(),
              e);
        }
      }
      long chunkElapsedMs = System.currentTimeMillis() - chunkStart;
      // C-3 실험용: 청크 1개(조회+처리 전체) 당 소요 시간을 남겨, Batch Size별 처리 속도를 비교한다.
      log.info(
          "[{}] 청크 #{} 처리 완료 - {}건, {}ms (건당 평균 {}ms)",
          label,
          chunkNumber,
          chunk.size(),
          chunkElapsedMs,
          String.format("%.2f", (double) chunkElapsedMs / chunk.size()));

      if (chunk.size() < chunkSize) {
        break;
      }
    }

    if (totalProcessed > 0 || totalFailed > 0) {
      log.info("[{}] 처리 완료 (성공 {}건, 실패 {}건)", label, totalProcessed, totalFailed);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void expireSingleReservation(Long reservationId, Consumer<Long> expireAction) {
    expireAction.accept(reservationId);
  }
}
