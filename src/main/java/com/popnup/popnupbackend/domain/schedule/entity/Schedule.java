package com.popnup.popnupbackend.domain.schedule.entity;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "schedules")
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

  // 팝업 스케쥴 등록
  public static Schedule createSchedule(
      Popup popup,
      LocalDate scheduleDate,
      LocalTime startTime,
      LocalTime endTime,
      Integer maxCapacity) {
    if (popup == null) {
      throw new IllegalArgumentException("스케쥴 등록을 위한 팝업 정보는 필수입니다.");
    }

    if (scheduleDate == null) {
      throw new IllegalArgumentException("스케쥴 날짜는 필수입니다.");
    }

    if (startTime == null || endTime == null) {
      throw new IllegalArgumentException("시작 시간과 종료 시간은 필수입니다.");
    }

    if (maxCapacity == null || maxCapacity <= 0) {
      throw new IllegalArgumentException("최대 수용 인원은 1명 이상이어야 합니다.");
    }

    if (!endTime.isAfter(startTime)) {
      throw new IllegalArgumentException("종료 시간은 시작 시간 이후여야 합니다.");
    }

    return new Schedule(popup, scheduleDate, startTime, endTime, maxCapacity);
  }

  // 예약 인원 추가
  public void addReservation(int count) {
    if (count <= 0) {
      throw new IllegalArgumentException("추가할 인원수는 1명 이상이어야 합니다.");
    }

    if (!this.isActive) {
      throw new IllegalStateException("해당 타임 슬롯은 현재 예약이 불가능합니다.");
    }

    if (this.nowCapacity + count > this.maxCapacity) {
      throw new IllegalStateException("정원을 초과했습니다.");
    }

    this.nowCapacity += count;
  }

  // 예약 취소
  public void cancelReservation(int count) {
    if (count <= 0) {
      throw new IllegalArgumentException("취소할 인원수는 1명 이상이어야 합니다.");
    }

    if (this.nowCapacity < count) {
      throw new IllegalStateException("취소하려는 인원이 현재 예약된 인원보다 많습니다.");
    }

    this.nowCapacity -= count;
  }

  public void updateActiveStatus(boolean isActive) {
    this.isActive = isActive;
  }
}
