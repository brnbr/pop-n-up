package com.popnup.popnupbackend.domain.schedule.repository;

import static com.popnup.popnupbackend.domain.schedule.entity.QSchedule.schedule;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ScheduleRepositoryCustomImpl implements ScheduleRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public List<Schedule> findActiveSchedulesByDate(Long popupId, LocalDate date) {
    return queryFactory
        .selectFrom(schedule)
        .where(
            schedule.popup.id.eq(popupId),
            schedule.scheduleDate.eq(date),
            schedule.isActive.isTrue())
        .orderBy(schedule.startTime.asc())
        .fetch();
  }
}
