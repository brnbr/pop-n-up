package com.popnup.popnupbackend.domain.schedule.repository;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
}
