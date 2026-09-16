package com.popnup.popnupbackend.domain.schedule.repository;

import static com.popnup.popnupbackend.domain.popup.entity.QPopup.popup;
import static com.popnup.popnupbackend.domain.schedule.entity.QSchedule.schedule;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ScheduleRepositoryCustomImpl implements ScheduleRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public List<Schedule> findActiveSchedulesByDate(Long popupId, LocalDate date) {
    return queryFactory
        .selectFrom(schedule)
        .join(schedule.popup, popup)
        .fetchJoin()
        .where(
            schedule.popup.id.eq(popupId),
            schedule.scheduleDate.eq(date),
            schedule.isActive.isTrue())
        .orderBy(schedule.startTime.asc())
        .fetch();
  }

  @Override
  public boolean existOverlappingSchedule(
      Long popupId, LocalDate scheduleDate, LocalTime startTime, LocalTime endTime) {
    Integer fetchOne =
        queryFactory
            .selectOne()
            .from(schedule)
            .where(
                schedule.popup.id.eq(popupId),
                schedule.scheduleDate.eq(scheduleDate),
                schedule.startTime.lt(endTime),
                schedule.endTime.gt(startTime))
            .fetchFirst();

    return fetchOne != null;
  }

  @Override
  public Optional<Schedule> findByIdWithPessimisticLock(Long id) {
    Schedule result =
        queryFactory
            .selectFrom(schedule)
            .where(schedule.id.eq(id))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint("jakarta.persistence.lock.timeout", 3000)
            .fetchOne();

    return Optional.ofNullable(result);
  }

  @Override
  public int tryIncreaseCapacity(Long scheduleId, int count) {
    long affectedRows =
        queryFactory
            .update(schedule)
            .set(schedule.nowCapacity, schedule.nowCapacity.add(count))
            .where(
                schedule.id.eq(scheduleId),
                schedule.nowCapacity.add(count).loe(schedule.maxCapacity))
            .execute();

    return (int) affectedRows;
  }

  @Override
  public Optional<Schedule> findByIdForValidation(Long id) {
    Schedule result =
        queryFactory
            .selectFrom(schedule)
            .join(schedule.popup, popup)
            .fetchJoin()
            .where(schedule.id.eq(id))
            .fetchOne();

    return Optional.ofNullable(result);
  }

  @Override
  public int tryDecreaseCapacity(Long scheduleId, int count) {
    long affectedRows =
        queryFactory
            .update(schedule)
            .set(schedule.nowCapacity, schedule.nowCapacity.subtract(count))
            .where(schedule.id.eq(scheduleId), schedule.nowCapacity.goe(count))
            .execute();

    return (int) affectedRows;
  }
}
