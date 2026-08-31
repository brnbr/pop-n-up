package com.popnup.popnupbackend.domain.schedule.entity;

import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Getter
@Table(name = "schedules")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
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
    private boolean isActive = true;

    public Schedule(Popup popup, LocalDate scheduleDate, LocalTime startTime, LocalTime endTime, Integer maxCapacity) {
        this.popup = popup;
        this.scheduleDate = scheduleDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.maxCapacity = maxCapacity;
        this.nowCapacity = 0;
        this.isActive = true;
    }

    public static Schedule createSchedule(Popup popup, LocalDate scheduleDate, LocalTime startTime, LocalTime endTime, Integer maxCapacity) {
        return new Schedule(popup, scheduleDate, startTime, endTime, maxCapacity);
    }

    //예약 추가
    private void addReservation(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("추가할 인원수는 1명 이상이어야 합니다.");
        }

        if (this.nowCapacity + count > this.maxCapacity) {
            throw new IllegalStateException("수용 정원을 초과했습니다.");
        }

        if (!this.isActive) {
            throw new IllegalStateException("해당 타임 슬롯은 현재 예약이 불가능합니다.");
        }

        this.nowCapacity += count;
    }

    //예약 취소
    private void cancelReservation(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("취소할 인원수는 1명 이상이어야 합니다.");
        }

        if (this.nowCapacity < count) {
            throw new IllegalStateException("취소하려는 인원(" + count + "명)이 현재 예약된 인원(" + this.nowCapacity + "명)보다 많습니다.");
        }

        this.nowCapacity -= count;
    }

    public void updateActiveStatus(boolean isActive) {
        this.isActive = isActive;
    }
}
