package com.popnup.popnupbackend.domain.schedule.repository;

import java.time.LocalDate;

public record ScheduleCapacitySnapshot(
    Long id, LocalDate scheduleDate, Integer maxCapacity, Integer nowCapacity) {}
