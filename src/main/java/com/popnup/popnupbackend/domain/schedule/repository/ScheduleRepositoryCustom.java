package com.popnup.popnupbackend.domain.schedule.repository;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepositoryCustom {
  List<Schedule> findActiveSchedulesByDate(Long popupId, LocalDate date);

  boolean existOverlappingSchedule(
      Long popupId, LocalDate scheduleDate, LocalTime startTime, LocalTime endTime);

  Optional<Schedule> findByIdWithPessimisticLock(Long id);

  int tryIncreaseCapacity(Long scheduleId, int count);

  Optional<Schedule> findByIdForValidation(Long id);

  int tryDecreaseCapacity(Long scheduleId, int count);
}
