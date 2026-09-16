package com.popnup.popnupbackend.domain.reservation.repository;

import static com.popnup.popnupbackend.domain.member.entity.QMember.member;
import static com.popnup.popnupbackend.domain.popup.entity.QPopup.popup;
import static com.popnup.popnupbackend.domain.reservation.entity.QReservation.reservation;
import static com.popnup.popnupbackend.domain.schedule.entity.QSchedule.schedule;

import com.popnup.popnupbackend.domain.reservation.entity.QReservation;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReservationRepositoryCustomImpl implements ReservationRepositoryCustom {

  private static final int SKIP_LOCKED = -2;

  private final JPAQueryFactory queryFactory;

  private JPAQuery<Reservation> selectReservationWithDetails() {
    return queryFactory
        .selectFrom(reservation)
        .join(reservation.member, member)
        .fetchJoin()
        .join(reservation.schedule, schedule)
        .fetchJoin()
        .join(schedule.popup, popup)
        .fetchJoin();
  }

  @Override
  public List<Reservation> getAllReservation(Long memberId) {
    return selectReservationWithDetails()
        .where(reservation.member.id.eq(memberId))
        .orderBy(reservation.createdAt.desc())
        .fetch();
  }

  @Override
  public boolean hasActiveReservation(Long scheduleId, Long memberId) {
    Integer fetchOne =
        queryFactory
            .selectOne()
            .from(reservation)
            .where(
                reservation.schedule.id.eq(scheduleId),
                reservation.member.id.eq(memberId),
                reservation.status.in(
                    ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.USED))
            .fetchFirst();

    return fetchOne != null;
  }

  // dsl 적용 후 수정
  @Override
  public List<Reservation> findAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status) {
    return selectReservationWithDetails()
        .where(popupIdEq(popupId), scheduleDateEq(scheduleDate), statusEq(status))
        .orderBy(
            reservation.schedule.scheduleDate.asc(),
            reservation.schedule.startTime.asc(),
            reservation.id.asc())
        .fetch();
  }

  @Override
  public Optional<Reservation> findByReservationNumberWithPessimisticLock(
      String reservationNumber) {
    Reservation result =
        queryFactory
            .selectFrom(reservation)
            .where(reservation.reservationNumber.eq(reservationNumber))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint("jakarta.persistence.lock.timeout", 3000)
            .fetchOne();

    return Optional.ofNullable(result);
  }

  @Override
  public Optional<Reservation> findByIdWithPessimisticLock(Long id) {
    Reservation result =
        queryFactory
            .selectFrom(reservation)
            .where(reservation.id.eq(id))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint("jakarta.persistence.lock.timeout", 3000)
            .fetchOne();

    return Optional.ofNullable(result);
  }

  @Override
  public int tryUpdateStatus(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses) {
    long affectedRows =
        queryFactory
            .update(reservation)
            .set(reservation.status, newStatus)
            .where(reservation.id.eq(reservationId), reservation.status.in(fromStatuses))
            .execute();

    return (int) affectedRows;
  }

  @Override
  public Optional<ScheduleAndPersonCount> findScheduleAndPersonCount(Long reservationId) {
    ScheduleAndPersonCount result =
        queryFactory
            .select(
                Projections.constructor(
                    ScheduleAndPersonCount.class, reservation.schedule.id, reservation.personCount))
            .from(reservation)
            .where(reservation.id.eq(reservationId))
            .fetchOne();

    return Optional.ofNullable(result);
  }

  @Override
  public Optional<ReservationStatus> findStatusById(Long reservationId) {
    ReservationStatus result =
        queryFactory
            .select(reservation.status)
            .from(reservation)
            .where(reservation.id.eq(reservationId))
            .fetchOne();

    return Optional.ofNullable(result);
  }

  @Override
  public List<Reservation> findExpiredReservationsChunk(
      LocalDate today, LocalTime currentTime, int chunkSize) {
    QReservation reservation = QReservation.reservation;

    return queryFactory
        .selectFrom(reservation)
        .join(reservation.schedule, schedule)
        .where(
            reservation.status.eq(ReservationStatus.CONFIRMED),
            schedule
                .scheduleDate
                .lt(today)
                .or(schedule.scheduleDate.eq(today).and(schedule.endTime.lt(currentTime))))
        .orderBy(reservation.id.asc())
        .limit(chunkSize)
        .fetch();
  }

  @Override
  public List<Reservation> findPendingReservationsChunk(
      ReservationStatus status, LocalDateTime deadline, int chunkSize) {
    QReservation reservation = QReservation.reservation;

    return queryFactory
        .selectFrom(reservation)
        .where(reservation.status.eq(status), reservation.createdAt.before(deadline))
        .orderBy(reservation.id.asc())
        .limit(chunkSize)
        .fetch();
  }

  @Override
  public List<Reservation> findPendingReservationsChunkForUpdate(
      ReservationStatus status, LocalDateTime deadline, int chunkSize) {
    QReservation reservation = QReservation.reservation;

    return queryFactory
        .selectFrom(reservation)
        .where(reservation.status.eq(status), reservation.createdAt.before(deadline))
        .orderBy(reservation.id.asc())
        .limit(chunkSize)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .setHint("jakarta.persistence.lock.timeout", SKIP_LOCKED)
        .fetch();
  }

  @Override
  public List<Reservation> findExpiredReservationsChunkForUpdate(
      LocalDate today, LocalTime currentTime, int chunkSize) {
    QReservation reservation = QReservation.reservation;

    return queryFactory
        .selectFrom(reservation)
        .join(reservation.schedule, schedule)
        .where(
            reservation.status.eq(ReservationStatus.CONFIRMED),
            schedule
                .scheduleDate
                .lt(today)
                .or(schedule.scheduleDate.eq(today).and(schedule.endTime.lt(currentTime))))
        .orderBy(reservation.id.asc())
        .limit(chunkSize)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .setHint("jakarta.persistence.lock.timeout", SKIP_LOCKED)
        .fetch();
  }

  private BooleanExpression popupIdEq(Long popupId) {
    return popupId != null ? reservation.schedule.popup.id.eq(popupId) : null;
  }

  private BooleanExpression scheduleDateEq(LocalDate scheduleDate) {
    return scheduleDate != null ? reservation.schedule.scheduleDate.eq(scheduleDate) : null;
  }

  private BooleanExpression statusEq(ReservationStatus status) {
    return status != null ? reservation.status.eq(status) : null;
  }
}
