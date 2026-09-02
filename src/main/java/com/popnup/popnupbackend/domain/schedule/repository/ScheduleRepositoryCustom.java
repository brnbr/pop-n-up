package com.popnup.popnupbackend.domain.schedule.repository;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import java.time.LocalDate;
import java.util.List;

public interface ScheduleRepositoryCustom {
  List<Schedule> findActiveSchedulesByDate(Long popupId, LocalDate date);
}
