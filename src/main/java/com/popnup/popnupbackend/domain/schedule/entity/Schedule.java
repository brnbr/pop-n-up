package com.popnup.popnupbackend.domain.schedule.entity;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "schedules",
    indexes = {
      @Index(
          name = "idx_schedule_unique",
          columnList = "popup_id, schedule_date, start_time",
          unique = true)
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "popup_id", nullable = false)
  private Popup popup;

  @Column(nullable = false)
  private LocalDate scheduleDate;

  @Column(nullable = false)
  private LocalTime startTime;

  @Column(nullable = false)
  private LocalTime endTime;

  @Column(nullable = false)
  private Integer maxCapacity;

  @Column(nullable = false)
  private Integer nowCapacity;

  @Column(nullable = false)
  private boolean isActive;

  private Schedule(
      Popup popup,
      LocalDate scheduleDate,
      LocalTime startTime,
      LocalTime endTime,
      Integer maxCapacity) {
    this.popup = popup;
    this.scheduleDate = scheduleDate;
    this.startTime = startTime;
    this.endTime = endTime;
    this.maxCapacity = maxCapacity;
    this.nowCapacity = 0;
    this.isActive = true;
  }

  // 팝업 스케줄 등록
  public static Schedule createSchedule(
      Popup popup,
      LocalDate scheduleDate,
      LocalTime startTime,
      LocalTime endTime,
      Integer maxCapacity) {
    if (maxCapacity == null || maxCapacity <= 0) {
      throw ScheduleErrorCode.INVALID_CAPACITY.toException();
    }

    if (!endTime.isAfter(startTime)) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }

    return new Schedule(popup, scheduleDate, startTime, endTime, maxCapacity);
  }

  // 예약 인원 추가
  public void addReservation(int count, LocalDateTime currentDateTime) {
    if (count <= 0) {
      throw ScheduleErrorCode.INVALID_CAPACITY.toException();
    }

    if (!this.isActive) {
      throw ScheduleErrorCode.SCHEDULE_INACTIVE.toException();
    }

    if (isAlreadyStarted(currentDateTime)) {
      throw ScheduleErrorCode.SCHEDULE_ALREADY_STARTED.toException();
    }

    if (this.popup.getStatus() != PopupStatus.OPEN) {
      throw ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND.toException();
    }

    if (this.nowCapacity + count > this.maxCapacity) {
      throw ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED.toException();
    }

    this.nowCapacity += count;
  }

  // 예약 취소 + 잔여석 복구
  public void cancelReservation(int count) {
    if (count <= 0) {
      throw ScheduleErrorCode.INVALID_CAPACITY.toException();
    }

    if (this.nowCapacity < count) {
      throw ScheduleErrorCode.INVALID_CANCEL_COUNT.toException();
    }

    this.nowCapacity -= count;
  }

  // 잔여석 계산
  public int getRemainingCapacity() {
    return Math.max(0, this.maxCapacity - this.nowCapacity);
  }

  // 매진 여부 판별
  public boolean isSoldOut() {
    return getRemainingCapacity() == 0;
  }

  // 시작된 회차인가? (신규 예약 불가)
  public boolean isAlreadyStarted(LocalDateTime currentDateTime) {
    LocalDateTime startDateTime = LocalDateTime.of(this.scheduleDate, this.startTime);
    return startDateTime.isBefore(currentDateTime);
  }

  public void updateActiveStatus(boolean isActive) {
    this.isActive = isActive;
  }
}
