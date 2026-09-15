package com.popnup.popnupbackend.domain.reservation.scheduler;

import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

  private final ReservationTimeoutProcessor reservationTimeoutProcessor;

  @Scheduled(cron = "${reservation.scheduler.payment-timeout-cron:0 * * * * *}")
  @SchedulerLock(
      name = "cancelExpiredPendingReservationsLock",
      lockAtMostFor = "${reservation.scheduler.payment-timeout-lock-at-most-for:50s}",
      lockAtLeastFor = "${reservation.scheduler.payment-timeout-lock-at-least-for:10s}")
  public void cancelExpiredPendingReservations() {
    log.debug("[Scheduler] 미결제 예약 만료 처리 스케줄러 시작");
    try {
      reservationTimeoutProcessor.payTimeOut();
      log.debug("[Scheduler] 미결제 예약 만료 처리 스케줄러 종료");
    } catch (Exception e) {
      log.error("[Scheduler] 미결제 예약 만료 처리 중 예외 발생", e);
    }
  }

  @Scheduled(cron = "${reservation.scheduler.no-show-cron:0 */10 * * * *}")
  @SchedulerLock(
      name = "expirePastConfirmedReservationLock",
      lockAtMostFor = "${reservation.scheduler.no-show-lock-at-most-for:9m}",
      lockAtLeastFor = "${reservation.scheduler.no-show-lock-at-least-for:30s}")
  public void expirePastConfirmedReservation() {
    log.debug("[Scheduler] 지난 회차 미방문 예약 만료 스케줄러 시작");
    try {
      reservationTimeoutProcessor.expirePastReservations();
      log.debug("[Scheduler] 지난 회차 미방문 예약 만료 스케줄러 종료");
    } catch (Exception e) {
      log.error("[Scheduler] 미방문 예약 만료 처리 중 예외 발생", e);
    }
  }
}
