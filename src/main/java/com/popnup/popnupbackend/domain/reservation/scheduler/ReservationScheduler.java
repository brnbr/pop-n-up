package com.popnup.popnupbackend.domain.reservation.scheduler;

import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationScheduler {

  private final ReservationTimeoutProcessor reservationTimeoutProcessor;

  @Scheduled(cron = "${reservation.scheduler.payment-timeout-cron:0 * * * * *}")
  @SchedulerLock(
      name = "expireUnpaidReservationsLock",
      lockAtMostFor = "${reservation.scheduler.payment-timeout-lock-at-most-for:50s}",
      lockAtLeastFor = "${reservation.scheduler.payment-timeout-lock-at-least-for:10s}")
  public void expireUnpaidReservations() {
    reservationTimeoutProcessor.expireUnpaidReservations();
  }

  @Scheduled(cron = "0 */5 * * * *")
  @SchedulerLock(
      name = "expirePastConfirmedReservationLock",
      lockAtMostFor = "4m",
      lockAtLeastFor = "30s")
  public void expirePastConfirmedReservation() {
    reservationTimeoutProcessor.expirePastReservations();
  }
}
