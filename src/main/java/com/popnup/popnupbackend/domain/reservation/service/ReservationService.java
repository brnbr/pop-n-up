package com.popnup.popnupbackend.domain.reservation.service;

import com.fasterxml.uuid.Generators;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberNotFoundException;
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
import java.time.LocalDate;
import java.time.LocalTime;
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

  // 예약 생성
  @Transactional
  public ReservationCreateResponse book(Long memberId, ReservationCreateRequest request) {
    Member member =
        memberRepository
            .findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException()); // todo 에러 처리 통일 필요

    Schedule schedule =
        scheduleRepository
            .findByIdWithPessimisticLock(request.getScheduleId())
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    if (reservationRepository.hasActiveReservation(schedule.getId(), memberId)) {
      throw ReservationErrorCode.DUPLICATE_USER_RESERVATION.toException();
    }

    schedule.addReservation(request.getPersonCount());

    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    String timeUUID =
        Generators.timeBasedGenerator()
            .generate()
            .toString()
            .replace("-", "")
            .substring(0, 8)
            .toUpperCase();
    String reservationNumber = "R" + today + timeUUID;

    Reservation reservation =
        Reservation.createReservation(
            reservationNumber, member, schedule, request.getPersonCount());
    Reservation savedReservation = reservationRepository.save(reservation);

    return ReservationCreateResponse.from(
        savedReservation.getId(), savedReservation.getReservationNumber());
  }

  // todo 결제 성공 시 예약 확정 처리
  // note QR 코드 생성 및 저장은 추가됨
  // 결제 시 예약 확정
  @Transactional
  public void confirmReservation(Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);
    reservation.confirm();
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
            .findByReservationNumber(request.getReservationNumber())
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

    reservationCancelManager.cancel(reservation);
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

  // 미사용 예약 만료 상태 변경
  @Transactional
  public void expirePastReservation() {
    LocalDate today = LocalDate.now();
    LocalTime nowTime = LocalTime.now();

    List<Reservation> expiredList = reservationRepository.findExpiredReservations(today, nowTime);

    if (expiredList.isEmpty()) {
      return;
    }

    log.info("[expiredPastReservation] 만료 처리 대상 건수: {}건", expiredList.size());

    for (Reservation reservation : expiredList) {
      reservation.expired();
    }
  }
}
