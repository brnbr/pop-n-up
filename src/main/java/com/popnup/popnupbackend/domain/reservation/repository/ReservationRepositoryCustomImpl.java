package com.popnup.popnupbackend.domain.reservation.repository;

import static com.popnup.popnupbackend.domain.member.entity.QMember.member;
import static com.popnup.popnupbackend.domain.popup.entity.QPopup.popup;
import static com.popnup.popnupbackend.domain.reservation.entity.QReservation.reservation;
import static com.popnup.popnupbackend.domain.schedule.entity.QSchedule.schedule;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReservationRepositoryCustomImpl implements ReservationRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public List<Reservation> getAllReservation(Long memberId) {
    return queryFactory
        .selectFrom(reservation)
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
    return queryFactory
        .selectFrom(reservation)
        .join(reservation.member, member)
        .fetchJoin()
        .join(reservation.schedule, schedule)
        .fetchJoin()
        .join(schedule.popup, popup)
        .fetchJoin()
        .where(popupIdEq(popupId), scheduleDateEq(scheduleDate), statusEq(status))
        .orderBy(
            reservation.schedule.scheduleDate.asc(),
            reservation.schedule.startTime.asc(),
            reservation.id.asc())
        .fetch();
  }

  @Override
  public Optional<Reservation> findByIdAndMemberId(Long reservationId, Long memberId) {
    Reservation result =
        queryFactory
            .selectFrom(reservation)
            .where(reservation.id.eq(reservationId), reservation.member.id.eq(memberId))
            .fetchOne();

    return Optional.ofNullable(result);
  }

  // dsl 적용 후 삭제
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
