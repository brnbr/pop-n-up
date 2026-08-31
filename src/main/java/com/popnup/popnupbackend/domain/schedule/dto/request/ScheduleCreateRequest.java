package com.popnup.popnupbackend.domain.schedule.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ScheduleCreateRequest {

  @NotNull(message = "팝업 ID는 필수입니다.")
  private Long popupId;
  @NotNull(message = "스케줄 날짜는 필수입니다.")
  @FutureOrPresent(message = "스케줄 날짜는 과거일 수 없습니다.")
  private LocalDate scheduleDate;
  @NotNull(message = "시작 시간은 필수입니다.")
  private LocalTime startTime;
  @NotNull(message = "종료 시간은 필수입니다.")
  private LocalTime endTime;
  @NotNull(message = "최대 수용 인원은 필수입니다.")
  @Min(value = 1, message = "최대 수용  인원은 1명 이상이어야 합니다.")
  private Integer maxCapacity;
}
