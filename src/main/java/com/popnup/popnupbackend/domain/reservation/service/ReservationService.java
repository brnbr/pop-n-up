package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.qrcode.service.QrService;
import com.popnup.popnupbackend.domain.reservation.dto.response.AdminReservationResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.support.KstTime;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ReservationService {

  private static final int MAX_PAGE_SIZE = 100;

  private final ReservationRepository reservationRepository;
  private final QrService qrService;
  private final ReservationCancelManager reservationCancelManager;
  private final TransactionTemplate transactionTemplate;

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
  public List<ReservationResponse> getMemberReservations(Long memberId, int page, int size) {
    return reservationRepository
        .findMemberReservations(memberId, safePage(page), safeSize(size))
        .stream()
        .map(ReservationResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ReservationResponse getMemberReservation(Long memberId, Long reservationId) {
    return reservationRepository
        .findByIdAndMemberId(reservationId, memberId)
        .map(ReservationResponse::from)
        .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);
  }

  @Transactional(readOnly = true)
  public List<AdminReservationResponse> getAdminReservations(
      Long popupId, LocalDate scheduleDate, ReservationStatus status, int page, int size) {
    return reservationRepository
        .findAdminReservations(popupId, scheduleDate, status, safePage(page), safeSize(size))
        .stream()
        .map(AdminReservationResponse::from)
        .toList();
  }

  private static int safePage(int page) {
    return Math.max(page, 0);
  }

  private static int safeSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }
}
