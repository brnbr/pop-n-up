package com.popnup.popnupbackend.domain.reservation.service;

import com.fasterxml.uuid.Generators;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
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
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

  private final ReservationRepository reservationRepository;
  private final ScheduleRepository scheduleRepository;
  private final MemberRepository memberRepository;
  private final QrService qrService;
  private final ReservationCancelManager reservationCancelManager;
  private final ScheduleCapacityCache scheduleCapacityCache;

  @RateLimiter(name = "reservationBooking")
  @Transactional
  public ReservationCreateResponse bookWithConditionalUpdate(
      Long memberId, ReservationCreateRequest request) {
    Member member =
        memberRepository
            .findById(memberId)
            .orElseThrow(MemberErrorCode.MEMBER_NOT_FOUND::toException);

    if (reservationRepository.hasActiveReservation(request.getScheduleId(), memberId)) {
      throw ReservationErrorCode.DUPLICATE_USER_RESERVATION.toException();
    }

    boolean passed =
        scheduleCapacityCache.tryReserve(request.getScheduleId(), request.getPersonCount());
    if (!passed) {
      throw ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED.toException();
    }

    try {
      String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
      String timeUUID =
          Generators.timeBasedGenerator()
              .generate()
              .toString()
              .replace("-", "")
              .substring(0, 8)
              .toUpperCase();
      String reservationNumber = "R" + today + timeUUID;

      Schedule schedule =
          scheduleRepository
              .findByIdForValidation(request.getScheduleId())
              .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);
      schedule.validateBookable(LocalDateTime.now());

      int affectedRows =
          scheduleRepository.tryIncreaseCapacity(request.getScheduleId(), request.getPersonCount());
      if (affectedRows == 0) {
        throw ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED.toException();
      }

      Reservation reservation =
          Reservation.createReservation(
              reservationNumber, member, schedule, request.getPersonCount());
      Reservation savedReservation = reservationRepository.save(reservation);

      return ReservationCreateResponse.from(
          savedReservation.getId(), savedReservation.getReservationNumber());

    } catch (Exception e) {
      // Redis 필터는 통과했지만 DB 단계(정원 초과, 비활성 스케줄, 팝업 미오픈 등 사유 불문)에서
      // 예약이 최종 실패한 모든 경우, Redis 카운터를 되돌려 다음 요청들의 판단 정확도를 유지
      scheduleCapacityCache.compensate(request.getScheduleId(), request.getPersonCount());
      throw e;
    }
  }

  // 결제 시 예약 확정
  @Transactional
  public void confirmReservation(Long reservationId, boolean paymentSucceeded) {
    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.confirm(paymentSucceeded);
  }

  // QR 생성
  @Transactional(readOnly = true)
  public byte[] getReservationQrCode(Long memberId, Long reservationId) {
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

    return qrService.generateQrCodeImage(reservation.getReservationNumber());
  }

  // 체크인
  @Transactional
  public CheckInResponse checkIn(CheckInRequest request) {
    Reservation reservation =
        reservationRepository
            .findByReservationNumberWithPessimisticLock(request.getReservationNumber())
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.checkIn();
    return CheckInResponse.from(reservation);
  }

  // 예약 취소
  @Transactional
  public void cancel(Long memberId, Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (!reservation.isOwnedBy(memberId)) {
      throw ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException();
    }

    reservationCancelManager.cancel(reservationId);
  }

  // 예약 목록 전체 조회
  @Transactional(readOnly = true)
  public List<ReservationResponse> allReservations(Long memberId) {
    return reservationRepository.getAllReservation(memberId).stream()
        .map(ReservationResponse::from)
        .toList();
  }

  // 예약 단 건 조회
  @Transactional(readOnly = true)
  public ReservationResponse oneReservation(Long memberId, Long reservationId) {
    return reservationRepository
        .findByIdAndMemberId(reservationId, memberId)
        .map(ReservationResponse::from)
        .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);
  }

  // 관리자 - 예약 목록 전체 조회
  @Transactional(readOnly = true)
  public List<AdminReservationResponse> getAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status) {
    return reservationRepository.findAdminReservations(popupId, scheduleDate, status).stream()
        .map(AdminReservationResponse::from)
        .toList();
  }
}
