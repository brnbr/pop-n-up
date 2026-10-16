package com.popnup.popnupbackend.domain.schedule.dto.response;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ScheduleResponse {

  private final Long scheduleId;
  private final String popupTitle;
  private final LocalDate scheduleDate;
  private final LocalTime startTime;
  private final LocalTime endTime;
  private final Integer maxCapacity;
  private final Integer nowCapacity;
  private final Integer remainingCapacity;
  private final boolean isActive;
  private final boolean isAvailable;
  private final boolean isPast;

  public static ScheduleResponse from(Schedule schedule, LocalDateTime now) {
    return of(
        schedule.getId(),
        schedule.getPopup().getTitle(),
        schedule.getScheduleDate(),
        schedule.getStartTime(),
        schedule.getEndTime(),
        schedule.getMaxCapacity(),
        schedule.getNowCapacity(),
        schedule.isActive(),
        now);
  }

  public static ScheduleResponse of(
      Long scheduleId,
      String popupTitle,
      LocalDate scheduleDate,
      LocalTime startTime,
      LocalTime endTime,
      int maxCapacity,
      int nowCapacity,
      boolean active,
      LocalDateTime now) {
    int remaining = Schedule.remainingOf(maxCapacity, nowCapacity);
    boolean started = Schedule.hasStarted(scheduleDate, startTime, now);
    boolean available = active && remaining > 0 && !started;

    return new ScheduleResponse(
        scheduleId,
        popupTitle,
        scheduleDate,
        startTime,
        endTime,
        maxCapacity,
        nowCapacity,
        remaining,
        active,
        available,
        started);
  }
}
