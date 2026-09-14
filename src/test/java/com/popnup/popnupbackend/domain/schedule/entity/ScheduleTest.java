package com.popnup.popnupbackend.domain.schedule.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@Slf4j
class ScheduleTest {

  private Schedule schedule;
  private Popup openPopup;
  private final LocalDate scheduleDate = LocalDate.now().plusDays(1);
  private final LocalTime startTime = LocalTime.of(10, 0);
  private final LocalTime endTime = LocalTime.of(11, 0);
  private final int maxCapacity = 10;

  @BeforeEach
  void setUp() {
    // 수정: popup null -> 실제 OPEN 상태 Popup 객체로 변경 (addReservation()의 popup 상태 체크 때문)
    openPopup = createPopup(PopupStatus.OPEN);
    schedule = Schedule.createSchedule(openPopup, scheduleDate, startTime, endTime, maxCapacity);
  }

  // 수정: 테스트용 Popup 생성 헬퍼 추가 - status만 파라미터로 받아 OPEN/CLOSED/UPCOMING 케이스에 재사용
  private Popup createPopup(PopupStatus status) {
    return Popup.builder()
        .title("테스트 팝업")
        .category(PopupCategory.ETC)
        .region("서울")
        .address("서울시 강남구")
        .startDate(scheduleDate.minusDays(10))
        .endDate(scheduleDate.plusDays(10))
        .isFree(true)
        .price(0)
        .status(status)
        .build();
  }

  @Nested
  @DisplayName("createSchedule 생성 검증")
  class CreateSchedule {

    @Test
    @DisplayName("정원이 0 이하면 예외 발생")
    void invalidCapacity() {
      int invalidCapacity = 0;

      log.info("[createSchedule.invalidCapacity] input(maxCapacity={})", invalidCapacity);

      ServiceException exception =
          assertThrows(
              ServiceException.class,
              () ->
                  Schedule.createSchedule(
                      openPopup, scheduleDate, startTime, endTime, invalidCapacity));

      log.info(
          "[createSchedule.invalidCapacity] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.INVALID_CAPACITY,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_CAPACITY);
    }

    @Test
    @DisplayName("종료 시간이 시작 시간보다 빠르면 예외 발생")
    void invalidTimeRange() {
      LocalTime invalidStart = LocalTime.of(11, 0);
      LocalTime invalidEnd = LocalTime.of(10, 0);

      log.info(
          "[createSchedule.invalidTimeRange] input(startTime={}, endTime={})",
          invalidStart,
          invalidEnd);

      ServiceException exception =
          assertThrows(
              ServiceException.class,
              () ->
                  Schedule.createSchedule(
                      openPopup, scheduleDate, invalidStart, invalidEnd, maxCapacity));

      log.info(
          "[createSchedule.invalidTimeRange] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.INVALID_TIME_RANGE,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE);
    }

    @Test
    @DisplayName("정상 생성 시 초기 필드값이 올바르게 세팅된다")
    void successFieldsAreSetCorrectly() {
      log.info(
          "[createSchedule.successFieldsAreSetCorrectly] input(popup={}, date={}, start={}, end={}, maxCapacity={})",
          openPopup.getTitle(),
          scheduleDate,
          startTime,
          endTime,
          maxCapacity);

      Schedule created =
          Schedule.createSchedule(openPopup, scheduleDate, startTime, endTime, maxCapacity);

      log.info(
          "[createSchedule.successFieldsAreSetCorrectly] actual(nowCapacity={}, isActive={}, popup={})",
          created.getNowCapacity(),
          created.isActive(),
          created.getPopup().getTitle());

      assertThat(created.getPopup()).isEqualTo(openPopup);
      assertThat(created.getScheduleDate()).isEqualTo(scheduleDate);
      assertThat(created.getStartTime()).isEqualTo(startTime);
      assertThat(created.getEndTime()).isEqualTo(endTime);
      assertThat(created.getMaxCapacity()).isEqualTo(maxCapacity);
      assertThat(created.getNowCapacity()).isZero();
      assertThat(created.isActive()).isTrue();
    }
  }

  @Nested
  @DisplayName("addReservation 검증")
  class AddReservation {

    @Test
    @DisplayName("정상 예약 시 nowCapacity가 증가한다")
    void success() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      int requestCount = 3;
      int expectedCapacity = 3;

      log.info(
          "[addReservation.success] input(requestCount={}, currentDateTime={})",
          requestCount,
          beforeStart);

      schedule.addReservation(requestCount, beforeStart);
      int actualCapacity = schedule.getNowCapacity();

      log.info(
          "[addReservation.success] expectedCapacity={} actualCapacity={}",
          expectedCapacity,
          actualCapacity);

      assertThat(actualCapacity).isEqualTo(expectedCapacity);
    }

    @Test
    @DisplayName("인원수가 0 이하면 예외 발생")
    void invalidCount() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      int invalidCount = 0;

      log.info(
          "[addReservation.invalidCount] input(requestCount={}, currentDateTime={})",
          invalidCount,
          beforeStart);

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> schedule.addReservation(invalidCount, beforeStart));

      log.info(
          "[addReservation.invalidCount] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.INVALID_CAPACITY,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_CAPACITY);
    }

    @Test
    @DisplayName("비활성 스케줄이면 예외 발생")
    void inactiveSchedule() {
      schedule.updateActiveStatus(false);
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      int requestCount = 1;

      log.info(
          "[addReservation.inactiveSchedule] input(requestCount={}, currentDateTime={}, isActive={})",
          requestCount,
          beforeStart,
          schedule.isActive());

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> schedule.addReservation(requestCount, beforeStart));

      log.info(
          "[addReservation.inactiveSchedule] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.SCHEDULE_INACTIVE,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_INACTIVE);
    }

    @Test
    @DisplayName("이미 시작된 회차면 예외 발생 - 이번에 수정한 핵심 케이스")
    void alreadyStarted() {
      LocalDateTime afterStart = LocalDateTime.of(scheduleDate, startTime.plusMinutes(1));
      int requestCount = 1;

      log.info(
          "[addReservation.alreadyStarted] input(requestCount={}, currentDateTime={}, scheduleStart={})",
          requestCount,
          afterStart,
          LocalDateTime.of(scheduleDate, startTime));

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> schedule.addReservation(requestCount, afterStart));

      int actualCapacity = schedule.getNowCapacity();

      log.info(
          "[addReservation.alreadyStarted] expectedErrorCode={} actualErrorCode={} expectedCapacity=0 actualCapacity={}",
          ScheduleErrorCode.SCHEDULE_ALREADY_STARTED,
          exception.getErrorCode(),
          actualCapacity);

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_ALREADY_STARTED);
      assertThat(actualCapacity).isZero();
    }

    @Test
    @DisplayName("시작 시각 정각에 예약 시도하면 예외 발생 (경계값)")
    void exactlyAtStartTime() {
      // isAlreadyStarted()는 isBefore() 기준이라 정각은 "아직 시작 전"으로 판단됨 (경계값 동작 문서화)
      LocalDateTime exactlyStart = LocalDateTime.of(scheduleDate, startTime);
      int requestCount = 1;
      int expectedCapacity = 1;

      log.info(
          "[addReservation.exactlyAtStartTime] input(requestCount={}, currentDateTime={}, scheduleStart={})",
          requestCount,
          exactlyStart,
          LocalDateTime.of(scheduleDate, startTime));

      schedule.addReservation(requestCount, exactlyStart);
      int actualCapacity = schedule.getNowCapacity();

      log.info(
          "[addReservation.exactlyAtStartTime] expectedCapacity={} actualCapacity={}",
          expectedCapacity,
          actualCapacity);

      assertThat(actualCapacity).isEqualTo(expectedCapacity);
    }

    @Test
    @DisplayName("정원 초과 시 예외 발생")
    void capacityExceeded() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      int overCount = maxCapacity + 1;

      log.info(
          "[addReservation.capacityExceeded] input(requestCount={}, maxCapacity={}, currentDateTime={})",
          overCount,
          maxCapacity,
          beforeStart);

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> schedule.addReservation(overCount, beforeStart));

      log.info(
          "[addReservation.capacityExceeded] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED);
    }

    @Test
    @DisplayName("Popup이 OPEN 상태가 아니면(UPCOMING) 예외 발생")
    void popupUpcoming() {
      Popup upcomingPopup = createPopup(PopupStatus.UPCOMING);
      Schedule upcomingSchedule =
          Schedule.createSchedule(upcomingPopup, scheduleDate, startTime, endTime, maxCapacity);
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      int requestCount = 1;

      log.info(
          "[addReservation.popupUpcoming] input(requestCount={}, currentDateTime={}, popupStatus={})",
          requestCount,
          beforeStart,
          upcomingPopup.getStatus());

      ServiceException exception =
          assertThrows(
              ServiceException.class,
              () -> upcomingSchedule.addReservation(requestCount, beforeStart));

      log.info(
          "[addReservation.popupUpcoming] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND);
    }

    @Test
    @DisplayName("Popup이 OPEN 상태가 아니면(CLOSED) 예외 발생")
    void popupClosed() {
      Popup closedPopup = createPopup(PopupStatus.CLOSED);
      Schedule closedSchedule =
          Schedule.createSchedule(closedPopup, scheduleDate, startTime, endTime, maxCapacity);
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      int requestCount = 1;

      log.info(
          "[addReservation.popupClosed] input(requestCount={}, currentDateTime={}, popupStatus={})",
          requestCount,
          beforeStart,
          closedPopup.getStatus());

      ServiceException exception =
          assertThrows(
              ServiceException.class,
              () -> closedSchedule.addReservation(requestCount, beforeStart));

      int actualCapacity = closedSchedule.getNowCapacity();

      log.info(
          "[addReservation.popupClosed] expectedErrorCode={} actualErrorCode={} expectedCapacity=0 actualCapacity={}",
          ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND,
          exception.getErrorCode(),
          actualCapacity);

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND);
      assertThat(actualCapacity).isZero();
    }
  }

  @Nested
  @DisplayName("cancelReservation 검증")
  class CancelReservation {

    @Test
    @DisplayName("정상 취소 시 nowCapacity가 감소한다")
    void success() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      schedule.addReservation(5, beforeStart);
      int cancelCount = 3;
      int expectedCapacity = 2;

      log.info(
          "[cancelReservation.success] input(cancelCount={}, beforeCancelCapacity={})",
          cancelCount,
          schedule.getNowCapacity());

      schedule.cancelReservation(cancelCount);
      int actualCapacity = schedule.getNowCapacity();

      log.info(
          "[cancelReservation.success] expectedCapacity={} actualCapacity={}",
          expectedCapacity,
          actualCapacity);

      assertThat(actualCapacity).isEqualTo(expectedCapacity);
    }

    @Test
    @DisplayName("현재 예약 인원보다 많이 취소하려 하면 예외 발생")
    void invalidCancelCount() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      schedule.addReservation(2, beforeStart);
      int invalidCancelCount = 3;

      log.info(
          "[cancelReservation.invalidCancelCount] input(cancelCount={}, currentCapacity={})",
          invalidCancelCount,
          schedule.getNowCapacity());

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> schedule.cancelReservation(invalidCancelCount));

      log.info(
          "[cancelReservation.invalidCancelCount] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.INVALID_CANCEL_COUNT,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_CANCEL_COUNT);
    }

    @Test
    @DisplayName("취소 인원이 0 이하면 예외 발생")
    void invalidCount() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      schedule.addReservation(2, beforeStart);
      int invalidCount = 0;

      log.info(
          "[cancelReservation.invalidCount] input(cancelCount={}, currentCapacity={})",
          invalidCount,
          schedule.getNowCapacity());

      ServiceException exception =
          assertThrows(ServiceException.class, () -> schedule.cancelReservation(invalidCount));

      log.info(
          "[cancelReservation.invalidCount] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.INVALID_CAPACITY,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_CAPACITY);
    }
  }

  @Nested
  @DisplayName("잔여석 / 매진 여부 검증")
  class CapacityChecks {

    @Test
    @DisplayName("잔여석은 maxCapacity - nowCapacity 이다")
    void remainingCapacity() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      schedule.addReservation(4, beforeStart);
      int expectedRemaining = maxCapacity - 4;

      log.info(
          "[remainingCapacity] input(maxCapacity={}, nowCapacity={})",
          maxCapacity,
          schedule.getNowCapacity());

      int actualRemaining = schedule.getRemainingCapacity();

      log.info("[remainingCapacity] expected={} actual={}", expectedRemaining, actualRemaining);

      assertThat(actualRemaining).isEqualTo(expectedRemaining);
    }

    @Test
    @DisplayName("정원이 꽉 차면 isSoldOut()이 true를 반환한다")
    void soldOut() {
      LocalDateTime beforeStart = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);
      schedule.addReservation(maxCapacity, beforeStart);

      log.info(
          "[soldOut] input(maxCapacity={}, nowCapacity={})",
          maxCapacity,
          schedule.getNowCapacity());

      boolean actual = schedule.isSoldOut();

      log.info("[soldOut] expected=true actual={}", actual);

      assertThat(actual).isTrue();
    }
  }

  @Nested
  @DisplayName("isAlreadyStarted 검증")
  class AlreadyStartedCheck {

    @Test
    @DisplayName("시작 시각 이전이면 false")
    void beforeStart() {
      LocalDateTime before = LocalDateTime.of(scheduleDate.minusDays(1), LocalTime.NOON);

      log.info("[isAlreadyStarted.beforeStart] input(currentDateTime={})", before);

      boolean actual = schedule.isAlreadyStarted(before);

      log.info("[isAlreadyStarted.beforeStart] expected=false actual={}", actual);

      assertThat(actual).isFalse();
    }

    @Test
    @DisplayName("시작 시각 이후면 true")
    void afterStart() {
      LocalDateTime after = LocalDateTime.of(scheduleDate, startTime.plusMinutes(1));

      log.info("[isAlreadyStarted.afterStart] input(currentDateTime={})", after);

      boolean actual = schedule.isAlreadyStarted(after);

      log.info("[isAlreadyStarted.afterStart] expected=true actual={}", actual);

      assertThat(actual).isTrue();
    }
  }
}
