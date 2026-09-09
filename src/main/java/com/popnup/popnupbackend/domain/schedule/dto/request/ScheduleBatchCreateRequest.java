package com.popnup.popnupbackend.domain.schedule.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;

@Getter
public class ScheduleBatchCreateRequest {

  @NotNull(message = "팝업 ID는 필수 입니다.")
  private Long popupId;

  @NotNull(message = "스케줄 일자는 필수입니다.")
  @FutureOrPresent(message = "과거 날짜에는 스케줄을 생성할 수 없습니다.")
  private LocalDate scheduleDate;

  @NotNull(message = "운영 시작 시각은 필수입니다.")
  private LocalTime openTime; // 예: 10:00

  @NotNull(message = "운영 종료 시각은 필수입니다.")
  private LocalTime closeTime; // 예: 20:00

  @NotNull(message = "회차 간격(분)은 필수입니다.")
  @Min(value = 10, message = "회차 간격은 최소 10분 이상이어야 합니다.")
  private Integer intervalMinutes; // 예: 30분, 60분

  @NotNull(message = "회차당 수용 인원은 필수입니다.")
  @Min(value = 1, message = "수용 인원은 최소 1명 이상이어야 합니다.")
  private Integer maxCapacity;
}
