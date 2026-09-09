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
  private final Integer remainingCapacity; // 잔여석 개수
  private final boolean isActive; // 예약 슬롯 활성화 여부
  private final boolean isAvailable; // 예약 가능 여부 (여석 존재 + 예약 슬롯 활성화 O)
  private final boolean isPast; // 이미 지난 시간인지

  public static ScheduleResponse from(Schedule schedule, LocalDateTime now) {
    int remaining = schedule.getRemainingCapacity();
    boolean started = schedule.isAlreadyStarted(now);

    boolean available = schedule.isActive() && remaining > 0 && !started;

    return new ScheduleResponse(
        schedule.getId(),
        schedule.getPopup().getTitle(),
        schedule.getScheduleDate(),
        schedule.getStartTime(),
        schedule.getEndTime(),
        schedule.getMaxCapacity(),
        schedule.getNowCapacity(),
        remaining,
        schedule.isActive(),
        available,
        started);
  }
}
