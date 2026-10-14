package com.popnup.popnupbackend.domain.schedule.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScheduleTest {

  private static final LocalDate DAY = LocalDate.of(2031, 5, 10);

  // 회차: 2031-05-10 10:00 ~ 10:50
  private Schedule schedule() {
    return Schedule.createSchedule(
        mock(Popup.class), DAY, LocalTime.of(10, 0), LocalTime.of(10, 50), 20);
  }

  @Test
  @DisplayName("체크인 가능: 회차 당일, 시작 전 일찍 도착")
  void checkIn_availableBeforeStartOnTheDay() {
    assertThat(schedule().isCheckInAvailable(LocalDateTime.of(DAY, LocalTime.of(9, 30)))).isTrue();
  }

  @Test
  @DisplayName("체크인 가능: 회차 진행 중")
  void checkIn_availableDuringSlot() {
    assertThat(schedule().isCheckInAvailable(LocalDateTime.of(DAY, LocalTime.of(10, 20)))).isTrue();
  }

  @Test
  @DisplayName("체크인 가능 경계: 종료 시각 정각까지")
  void checkIn_availableAtEndBoundary() {
    assertThat(schedule().isCheckInAvailable(LocalDateTime.of(DAY, LocalTime.of(10, 50)))).isTrue();
  }

  @Test
  @DisplayName("체크인 불가: 종료 시각이 지난 뒤")
  void checkIn_unavailableAfterEnd() {
    assertThat(schedule().isCheckInAvailable(LocalDateTime.of(DAY, LocalTime.of(10, 51))))
        .isFalse();
  }

  @Test
  @DisplayName("체크인 불가: 전날 (어제 QR)")
  void checkIn_unavailableOnPreviousDay() {
    assertThat(
            schedule().isCheckInAvailable(LocalDateTime.of(DAY.minusDays(1), LocalTime.of(10, 20))))
        .isFalse();
  }

  @Test
  @DisplayName("체크인 불가: 다음 날 (지난 회차)")
  void checkIn_unavailableOnNextDay() {
    assertThat(
            schedule().isCheckInAvailable(LocalDateTime.of(DAY.plusDays(1), LocalTime.of(10, 20))))
        .isFalse();
  }
}
