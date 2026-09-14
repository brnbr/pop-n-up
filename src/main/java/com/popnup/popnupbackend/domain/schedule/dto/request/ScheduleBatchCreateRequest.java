package com.popnup.popnupbackend.domain.schedule.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleBatchCreateRequest {

  @NotNull(message = "팝업 ID는 필수 입니다.")
  private Long popupId;

  @NotNull(message = "스케줄 날짜는 필수입니다.")
  @FutureOrPresent(message = "과거 날짜에는 스케줄을 생성할 수 없습니다.")
  private LocalDate scheduleDate;

  @NotNull(message = "운영 시작 시각은 필수입니다.")
  private LocalTime openTime;

  @NotNull(message = "운영 종료 시각은 필수입니다.")
  private LocalTime closeTime;

  @NotNull(message = "회차 간격(분)은 필수입니다.")
  @Min(value = 30, message = "회차 간격은 최소 30분 이상이어야 합니다.")
  private Integer intervalMinutes;

  @NotNull(message = "회차당 수용 인원은 필수입니다.")
  @Min(value = 1, message = "수용 인원은 최소 1명 이상이어야 합니다.")
  private Integer maxCapacity;

  @AssertTrue(message = "운영 종료 시각은 시작 시각보다 이후여야 합니다.")
  public boolean isTimeRangeValid() {
    if (openTime == null || closeTime == null) {
      return true;
    }
    return closeTime.isAfter(openTime);
  }
}
