package com.popnup.popnupbackend.domain.reservation.queue;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "reservation.queue.enabled", havingValue = "true")
public class ReservationQueueScheduler {

  private static final int TICKS_PER_SECOND = 5;

  private final ReservationQueueService queueService;
  private final int perTick;

  public ReservationQueueScheduler(
      ReservationQueueService queueService,
      @Value("${reservation.queue.admit-per-second:200}") long admitPerSecond) {
    this.queueService = queueService;
    this.perTick = (int) Math.max(1, admitPerSecond / TICKS_PER_SECOND);
  }

  @Scheduled(fixedRate = 1000 / TICKS_PER_SECOND)
  @SchedulerLock(name = "reservationQueueAdmitLock", lockAtMostFor = "2s", lockAtLeastFor = "100ms")
  public void allocate() {
    try {
      queueService.allocate(perTick);
    } catch (Exception e) {
      log.warn("[ReservationQueue] 좌석 확정 실패: {}", e.toString());
    }
  }
}
