package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.qrcode.service.QrService;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.AdminReservationResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import com.popnup.popnupbackend.domain.schedule.support.KstTime;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

  private static final int MAX_PERSON_COUNT = 10;
  private static final int MAX_PAGE_SIZE = 100;

  private final ReservationRepository reservationRepository;
  private final ScheduleRepository scheduleRepository;
  private final MemberRepository memberRepository;
  private final QrService qrService;
  private final ReservationCancelManager reservationCancelManager;
  private final ScheduleCapacityCache scheduleCapacityCache;
  private final ReservationNumberGenerator reservationNumberGenerator;
  private final TransactionTemplate transactionTemplate;

  @Value("${reservation.booking.sold-out-precheck:true}")
  private boolean soldOutPrecheck;

  @RateLimiter(name = "reservationBooking")
  public ReservationCreateResponse bookWithConditionalUpdate(
      Long memberId, ReservationCreateRequest request) {
    if (request.getPersonCount() == null
        || request.getPersonCount() < 1
        || request.getPersonCount() > MAX_PERSON_COUNT) {
      throw ReservationErrorCode.INVALID_PERSON_COUNT.toException();
    }

    if (soldOutPrecheck
        && scheduleCapacityCache.isSoldOut(request.getScheduleId(), request.getPersonCount())) {
      throw ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED.toException();
    }

    return transactionTemplate.execute(status -> book(memberId, request));
  }

  private ReservationCreateResponse book(Long memberId, ReservationCreateRequest request) {
    Member member = findMember(memberId);
    validateNoActiveReservation(request.getScheduleId(), memberId);
    reserveInRedis(request);

    try {
      Reservation reservation = createReservation(member, request);
      return ReservationCreateResponse.from(
          reservation.getId(), reservation.getReservationNumber());
    } catch (Exception e) {
      scheduleCapacityCache.compensate(request.getScheduleId(), request.getPersonCount());
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

  @Transactional
  public void confirmReservation(Long reservationId, boolean paymentSucceeded) {
    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.confirm(paymentSucceeded);
  }

  public byte[] getReservationQrCode(Long memberId, Long reservationId) {
    String reservationNumber =
        readOnlyTransaction().execute(status -> findQrTargetNumber(memberId, reservationId));
    return qrService.generateQrCodeImage(reservationNumber);
  }

  private String findQrTargetNumber(Long memberId, Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (!reservation.isOwnedBy(memberId)) {
      throw ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException();
    }

    if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }

    return reservation.getReservationNumber();
  }

  private TransactionTemplate readOnlyTransaction() {
    TransactionTemplate readOnly =
        new TransactionTemplate(transactionTemplate.getTransactionManager());
    readOnly.setReadOnly(true);
    return readOnly;
  }

  @Transactional
  public void cancel(Long memberId, Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (!reservation.isOwnedBy(memberId)) {
      throw ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException();
    }

    if (reservation.getStatus() == ReservationStatus.CONFIRMED
        && reservation.getSchedule().isAlreadyStarted(KstTime.now())) {
      throw ReservationErrorCode.CANCEL_NOT_ALLOWED_AFTER_START.toException();
    }

    reservationCancelManager.cancel(reservationId);
  }

  @Transactional(readOnly = true)
  public List<ReservationResponse> allReservations(Long memberId, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return reservationRepository.getAllReservation(memberId, safePage, safeSize).stream()
        .map(ReservationResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ReservationResponse oneReservation(Long memberId, Long reservationId) {
    return reservationRepository
        .findByIdAndMemberId(reservationId, memberId)
        .map(ReservationResponse::from)
        .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);
  }

  @Transactional(readOnly = true)
  public List<AdminReservationResponse> getAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return reservationRepository
        .findAdminReservations(popupId, scheduleDate, status, safePage, safeSize)
        .stream()
        .map(AdminReservationResponse::from)
        .toList();
  }
}
