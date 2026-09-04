package com.popnup.popnupbackend.domain.reservation.service;

import com.fasterxml.uuid.Generators;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberNotFoundException;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

  private final ReservationRepository reservationRepository;
  private final ScheduleRepository scheduleRepository;
  private final MemberRepository memberRepository;

  // 예약 생성
  @Transactional
  public ReservationCreateResponse book(Long memberId, ReservationCreateRequest request) {
    Member member =
        memberRepository
            .findById(memberId)
            .orElseThrow(() -> new MemberNotFoundException()); // 에러 처리 통일 필요

    Schedule schedule =
        scheduleRepository
            .findByIdWithPessimisticLock(request.getScheduleId())
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    // 중복 예약 검사
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

  /* 결제 성공 시 예약 확정 처리
    - 결제 도메인 도입 후 보완 필요
  */
  @Transactional
  public void confirmReservation(Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);
    reservation.confirm();
  }

  // 예약 취소
  @Transactional
  public void cancel(Long memberId, Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (!reservation.getMember().getId().equals(memberId)) {
      throw ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException();
    }

    reservation.cancel();

    // 락 추가
    Long scheduleId = reservation.getSchedule().getId();
    Schedule schedule =
        scheduleRepository
            .findByIdWithPessimisticLock(scheduleId)
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    schedule.cancelReservation(reservation.getPersonCount());
  }

  // 예약 목록 조회
  @Transactional(readOnly = true)
  public List<ReservationResponse> allReservations(Long memberId) {
    return reservationRepository.getAllReservation(memberId).stream()
        .map(ReservationResponse::from)
        .toList();
  }

  // 단 건 조회
  @Transactional(readOnly = true)
  public ReservationResponse oneReservation(Long memberId, Long reservationId) {
    return reservationRepository
        .findByIdAndMemberId(reservationId, memberId)
        .map(ReservationResponse::from)
        .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);
  }

  // 관리자 - 예약 목록 조회
  @Transactional(readOnly = true)
  public List<AdminReservationResponse> getAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status) {
    return reservationRepository.findAdminReservations(popupId, scheduleDate, status).stream()
        .map(AdminReservationResponse::from)
        .toList();
  }

  // 결제 타임아웃 시 예약 취소
  @Transactional
  public void payTimeOut() {
    LocalDateTime deadLine = LocalDateTime.now().minusMinutes(10);

    List<Reservation> deadReservations =
        reservationRepository.findByStatusAndCreatedAtBefore(ReservationStatus.PENDING, deadLine);

    for (Reservation dr : deadReservations) {
      dr.cancel();

      Long scheduleId = dr.getSchedule().getId();
      Schedule schedule =
          scheduleRepository
              .findByIdWithPessimisticLock(scheduleId)
              .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);
      schedule.cancelReservation(dr.getPersonCount());
    }
  }
}
