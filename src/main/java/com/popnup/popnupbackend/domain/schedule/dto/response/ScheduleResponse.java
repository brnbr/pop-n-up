package com.popnup.popnupbackend.domain.schedule.dto.response;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@RequiredArgsConstructor
public class ScheduleResponse {

    private final Long id;
    private final LocalDate scheduleDate;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final Integer maxCapacity;
    private final Integer nowcapacity;
    private final Integer remainingCapacity;  //잔여석 개수
    private final boolean isActive;           //예약 슬롯 활성화 여부
    private final boolean isAvailable;        //예약 가능 여부 (여석 존재 + 예약 슬롯 활성화 O)

    public static ScheduleResponse from(Schedule schedule) {
        int remaining = schedule.getMaxCapacity() - schedule.getNowCapacity();
        boolean availiable = schedule.isActive() && remaining > 0;

        return new ScheduleResponse(
                schedule.getId(),
                schedule.getScheduleDate(),
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getMaxCapacity(),
                schedule.getNowCapacity(),
                remaining,
                schedule.isActive(),
                availiable
        );
    }
}
