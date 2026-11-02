package com.popnup.popnupbackend.domain.schedule.scheduler;

import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityResyncer;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "schedule.capacity-resync.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ScheduleCapacityResyncScheduler {

  private final ScheduleCapacityResyncer scheduleCapacityResyncer;

  @Scheduled(cron = "${schedule.capacity-resync.cron:0 * * * * *}")
  @SchedulerLock(
      name = "resyncScheduleCapacityLock",
      lockAtMostFor = "${schedule.capacity-resync.lock-at-most-for:50s}",
      lockAtLeastFor = "${schedule.capacity-resync.lock-at-least-for:5s}")
  public void resync() {
    scheduleCapacityResyncer.resync();
  }
}
