package com.popnup.popnupbackend.domain.schedule.entity;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Getter
@DynamicUpdate
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

  public static List<Schedule> createSlots(
      Popup popup,
      LocalDate scheduleDate,
      LocalTime openTime,
      LocalTime closeTime,
      int intervalMinutes,
      Integer maxCapacity) {
    if (intervalMinutes <= 0) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }

    List<Schedule> slots = new ArrayList<>();
    LocalTime start = openTime;
    while (start.isBefore(closeTime)) {
      LocalTime end = start.plusMinutes(intervalMinutes);
      if (end.isAfter(closeTime) || end.isBefore(start)) {
        break;
      }
      slots.add(createSchedule(popup, scheduleDate, start, end, maxCapacity));
      start = end;
    }

    if (slots.isEmpty()) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }
    return slots;
  }

  public static int remainingOf(int maxCapacity, int nowCapacity) {
    return Math.max(0, maxCapacity - nowCapacity);
  }

  public static boolean hasStarted(
      LocalDate scheduleDate, LocalTime startTime, LocalDateTime currentDateTime) {
    return LocalDateTime.of(scheduleDate, startTime).isBefore(currentDateTime);
  }

  public void validateBookable(LocalDateTime currentDateTime) {
    if (!this.isActive) {
      throw ScheduleErrorCode.SCHEDULE_INACTIVE.toException();
    }
    if (isAlreadyStarted(currentDateTime)) {
      throw ScheduleErrorCode.SCHEDULE_ALREADY_STARTED.toException();
    }
    if (this.popup.getStatus() != PopupStatus.OPEN) {
      throw ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND.toException();
    }
  }

  public boolean isAlreadyStarted(LocalDateTime currentDateTime) {
    return hasStarted(this.scheduleDate, this.startTime, currentDateTime);
  }

  public boolean isCheckInAvailable(LocalDateTime currentDateTime) {
    return this.scheduleDate.equals(currentDateTime.toLocalDate())
        && !currentDateTime.toLocalTime().isAfter(this.endTime);
  }

  public void updateActiveStatus(boolean isActive) {
    this.isActive = isActive;
  }
}
