package com.popnup.popnupbackend.domain.schedule.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
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
  @DisplayName("체크인 가능: 회차 시작 후 20분 이내")
  void checkIn_availableWithinGrace() {
    assertThat(schedule().isCheckInAvailable(LocalDateTime.of(DAY, LocalTime.of(10, 10)))).isTrue();
  }

  @Test
  @DisplayName("체크인 가능 경계: 시작 20분 후 정각까지")
  void checkIn_availableAtGraceBoundary() {
    assertThat(schedule().isCheckInAvailable(LocalDateTime.of(DAY, LocalTime.of(10, 20)))).isTrue();
  }

  @Test
  @DisplayName("체크인 불가: 시작 20분이 지난 뒤 (회차가 아직 진행 중이어도 노쇼)")
  void checkIn_unavailableAfterGrace() {
    assertThat(schedule().isCheckInAvailable(LocalDateTime.of(DAY, LocalTime.of(10, 21))))
        .isFalse();
  }

  @Test
  @DisplayName("체크인 가능: 자정 직전 시작 회차는 자정을 넘겨도 시작 20분 후까지")
  void checkIn_graceCrossesMidnight() {
    Schedule lateNight =
        Schedule.createSchedule(
            mock(Popup.class), DAY, LocalTime.of(23, 50), LocalTime.of(23, 59), 20);

    assertThat(lateNight.isCheckInAvailable(LocalDateTime.of(DAY.plusDays(1), LocalTime.of(0, 5))))
        .isTrue();
    assertThat(lateNight.isCheckInAvailable(LocalDateTime.of(DAY.plusDays(1), LocalTime.of(0, 11))))
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

  @Test
  @DisplayName("슬롯 생성: 간격이 없거나 0 이하이면 INVALID_TIME_RANGE (무한 반복 방지)")
  void createSlots_missingOrNonPositiveInterval_isInvalid() {
    for (Integer interval : new Integer[] {null, 0, -30}) {
      assertThatThrownBy(
              () ->
                  Schedule.createSlots(
                      mock(Popup.class),
                      DAY,
                      LocalTime.of(10, 0),
                      LocalTime.of(12, 0),
                      interval,
                      20))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE));
    }
  }

  @Test
  @DisplayName("슬롯 생성: 종료 시각이 시작 시각과 같거나 빠르면 INVALID_TIME_RANGE")
  void createSlots_closeNotAfterOpen_isInvalid() {
    for (LocalTime closeTime : new LocalTime[] {LocalTime.of(10, 0), LocalTime.of(9, 0)}) {
      assertThatThrownBy(
              () ->
                  Schedule.createSlots(
                      mock(Popup.class), DAY, LocalTime.of(10, 0), closeTime, 60, 20))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE));
    }
  }

  @Test
  @DisplayName("슬롯 생성: 정원이 0 이하이면 INVALID_CAPACITY")
  void createSlots_invalidCapacity() {
    assertThatThrownBy(
            () ->
                Schedule.createSlots(
                    mock(Popup.class), DAY, LocalTime.of(10, 0), LocalTime.of(12, 0), 60, 0))
        .isInstanceOf(ServiceException.class)
        .satisfies(
            e ->
                assertThat(((ServiceException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.INVALID_CAPACITY));
  }

  @Test
  @DisplayName("시작 판정: 시작 시각이 기준 시각보다 이전일 때만 시작한 것이다")
  void hasStarted() {
    LocalTime ten = LocalTime.of(10, 0);

    assertThat(Schedule.hasStarted(DAY, ten, LocalDateTime.of(DAY, LocalTime.of(10, 1)))).isTrue();
    assertThat(Schedule.hasStarted(DAY, ten, LocalDateTime.of(DAY, ten))).isFalse();
    assertThat(Schedule.hasStarted(DAY, ten, LocalDateTime.of(DAY, LocalTime.of(9, 59)))).isFalse();
  }

  @Test
  @DisplayName("예약 가능 검증: 팝업이 OPEN이 아니면 POPUP_NOT_OPEN, OPEN이면 통과한다")
  void validateBookable_popupMustBeOpen() {
    LocalDateTime beforeStart = LocalDateTime.of(DAY.minusDays(1), LocalTime.of(9, 0));

    Popup upcoming = mock(Popup.class);
    given(upcoming.getStatus()).willReturn(PopupStatus.UPCOMING);
    Schedule notOpen =
        Schedule.createSchedule(upcoming, DAY, LocalTime.of(10, 0), LocalTime.of(10, 50), 20);
    assertThatThrownBy(() -> notOpen.validateBookable(beforeStart))
        .isInstanceOf(ServiceException.class)
        .satisfies(
            e ->
                assertThat(((ServiceException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.POPUP_NOT_OPEN));

    Popup open = mock(Popup.class);
    given(open.getStatus()).willReturn(PopupStatus.OPEN);
    Schedule openSchedule =
        Schedule.createSchedule(open, DAY, LocalTime.of(10, 0), LocalTime.of(10, 50), 20);
    openSchedule.validateBookable(beforeStart);
  }
}
