package com.popnup.popnupbackend.domain.reservation.repository;

import static com.popnup.popnupbackend.domain.reservation.entity.QReservation.reservation;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
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
}
