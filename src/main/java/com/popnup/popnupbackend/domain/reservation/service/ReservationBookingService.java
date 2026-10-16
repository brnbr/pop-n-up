package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import com.popnup.popnupbackend.domain.schedule.support.KstTime;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ReservationBookingService {

  private static final int MAX_PERSON_COUNT = 10;

  private final ReservationRepository reservationRepository;
  private final ScheduleRepository scheduleRepository;
  private final MemberRepository memberRepository;
  private final ScheduleCapacityCache scheduleCapacityCache;
  private final ReservationNumberGenerator reservationNumberGenerator;
  private final TransactionTemplate transactionTemplate;

  @Value("${reservation.booking.sold-out-precheck:true}")
  private boolean soldOutPrecheck;

  @RateLimiter(name = "reservationBooking")
  public ReservationCreateResponse book(Long memberId, ReservationCreateRequest request) {
    if (request.getPersonCount() == null
        || request.getPersonCount() < 1
        || request.getPersonCount() > MAX_PERSON_COUNT) {
      throw ReservationErrorCode.INVALID_PERSON_COUNT.toException();
    }

    if (soldOutPrecheck
        && scheduleCapacityCache.isSoldOut(request.getScheduleId(), request.getPersonCount())) {
      throw ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED.toException();
    }

    return transactionTemplate.execute(status -> bookInTransaction(memberId, request));
  }

  private ReservationCreateResponse bookInTransaction(
      Long memberId, ReservationCreateRequest request) {
    Member member = findMember(memberId);
    validateNoActiveReservation(request.getScheduleId(), memberId);
    reserveInRedis(request);

    try {
      Reservation reservation = createReservation(member, request);
      return ReservationCreateResponse.from(
          reservation.getId(), reservation.getReservationNumber());
    } catch (Exception e) {
      scheduleCapacityCache.releaseNow(request.getScheduleId(), request.getPersonCount());
      throw e;
    }
  }

  private Member findMember(Long memberId) {
    return memberRepository
        .findById(memberId)
        .orElseThrow(MemberErrorCode.MEMBER_NOT_FOUND::toException);
  }

  private void validateNoActiveReservation(Long scheduleId, Long memberId) {
    if (reservationRepository.hasActiveReservation(scheduleId, memberId)) {
      throw ReservationErrorCode.DUPLICATE_USER_RESERVATION.toException();
    }
  }

  private void reserveInRedis(ReservationCreateRequest request) {
    boolean passed =
        scheduleCapacityCache.tryReserve(request.getScheduleId(), request.getPersonCount());
    if (!passed) {
      throw ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED.toException();
    }
  }

  private Reservation createReservation(Member member, ReservationCreateRequest request) {
    Schedule schedule =
        scheduleRepository
            .findWithPopupById(request.getScheduleId())
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);
    schedule.validateBookable(KstTime.now());

    increaseCapacity(request);

    return saveReservation(
        Reservation.createReservation(
            reservationNumberGenerator.generate(), member, schedule, request.getPersonCount()));
  }

  private void increaseCapacity(ReservationCreateRequest request) {
    int affectedRows =
        scheduleRepository.tryIncreaseCapacity(request.getScheduleId(), request.getPersonCount());
    if (affectedRows == 0) {
      throw ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED.toException();
    }
  }

  private Reservation saveReservation(Reservation reservation) {
    try {
      return reservationRepository.save(reservation);
    } catch (DataIntegrityViolationException e) {
      if (isActiveMemberViolation(e)) {
        throw ReservationErrorCode.DUPLICATE_USER_RESERVATION.toException();
      }
      throw e;
    }
  }

  private boolean isActiveMemberViolation(DataIntegrityViolationException e) {
    String message = e.getMostSpecificCause().getMessage();
    return message != null && message.toLowerCase().contains(Reservation.UK_ACTIVE_MEMBER);
  }
}
