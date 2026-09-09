package com.popnup.popnupbackend.domain.reservation.scheduler;

import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

  private final ReservationService reservationService;
  private final ReservationTimeoutProcessor reservationTimeoutProcessor;

  // 미결제 타임아웃 예약 자동 취소 (매 분 실행)
  @Scheduled(cron = "0 * * * * *")
  public void cancelExpiredPendingReservations() {
    log.debug("[Scheduler] 미결제 예약 만료 처리 스케줄러 시작");
    try {
      reservationTimeoutProcessor.payTimeOut();
      log.debug("[Scheduler] 미결제 예약 만료 처리 스케줄러 종료");
    } catch (Exception e) {
      log.error("[Scheduler] 미결제 예약 만료 처리 중 예외 발생", e);
    }
  }

  @Scheduled(cron = "0 */10 * * * *")
  public void expirePastConfirmedReservation() {
    log.debug("[Scheduler] 지난 회차 미방문 예약 만료 스케줄러 시작");
    try {
      reservationService.expirePastReservation();
      log.debug("[Scheduler] 지난 회차 미방문 예약 만료 스케줄러 종료");
    } catch (Exception e) {
      log.error("[Scheduler] 미방문 예약 만료 처리 중 예외 발생", e);
    }
  }
}
