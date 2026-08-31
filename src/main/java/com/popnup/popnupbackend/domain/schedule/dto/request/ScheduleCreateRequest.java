package com.popnup.popnupbackend.domain.schedule.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;

@Getter
public class ScheduleCreateRequest {

  private Long popupId;
  private LocalDate scheduleDate;
  private LocalTime startTime;
  private LocalTime endTime;
  private Integer maxCapacity;
}
