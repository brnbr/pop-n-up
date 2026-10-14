package com.popnup.popnupbackend.domain.schedule.repository;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleRepository
    extends JpaRepository<Schedule, Long>, ScheduleRepositoryCustom {
  @Query(
      "select new com.popnup.popnupbackend.domain.schedule.repository.ScheduleCapacitySnapshot("
          + "s.id, s.scheduleDate, s.maxCapacity, s.nowCapacity) "
          + "from Schedule s where s.scheduleDate >= :from and s.id > :afterId order by s.id asc")
  List<ScheduleCapacitySnapshot> findCapacitySnapshots(
      @Param("from") LocalDate from, @Param("afterId") Long afterId, Pageable pageable);
}
