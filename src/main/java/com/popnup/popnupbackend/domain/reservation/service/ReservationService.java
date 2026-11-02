package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.AdminReservationResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.CursorPage;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.AdminCursor;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.MemberCursor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {

  private static final int MAX_PAGE_SIZE = 100;

  private final ReservationRepository reservationRepository;
  private final QrService qrService;
  private final ReservationCancelManager reservationCancelManager;
  private final TransactionTemplate readOnlyTransaction;

  public ReservationService(
      ReservationRepository reservationRepository,
      QrService qrService,
      ReservationCancelManager reservationCancelManager,
      PlatformTransactionManager transactionManager) {
    this.reservationRepository = reservationRepository;
    this.qrService = qrService;
    this.reservationCancelManager = reservationCancelManager;
    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);
  }

  @Transactional
  public void confirmReservation(Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.confirm();
  }

  @Transactional
  public CheckInResponse checkIn(CheckInRequest request) {
    Reservation reservation =
        reservationRepository
            .findByReservationNumberWithPessimisticLock(request.getReservationNumber())
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.checkIn(LocalDateTime.now());
    return CheckInResponse.from(reservation);
  }

  public byte[] getReservationQrCode(Long memberId, Long reservationId) {
    String reservationNumber =
        readOnlyTransaction.execute(status -> findQrTargetNumber(memberId, reservationId));
    return qrService.generateQrCodeImage(reservationNumber);
  }

  private String findQrTargetNumber(Long memberId, Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.validateOwnedBy(memberId);
    reservation.validateQrIssuable();
    return reservation.getReservationNumber();
  }

  @Transactional
  public void cancel(Long memberId, Long reservationId) {
    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.validateOwnedBy(memberId);
    reservation.validateCancelable(LocalDateTime.now());
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
    Reservation reservation =
        reservationRepository
            .findById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    reservation.validateOwnedBy(memberId);
    return ReservationResponse.from(reservation);
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

  @Transactional(readOnly = true)
  public CursorPage<ReservationResponse> getMemberReservationsByCursor(
      Long memberId, String cursor, int size) {
    int limit = safeSize(size);
    List<Reservation> rows =
        reservationRepository.findMemberReservationsAfter(
            memberId, ReservationCursor.decodeMember(cursor), limit + 1);
    return toPage(
        rows,
        limit,
        ReservationResponse::from,
        last -> ReservationCursor.encode(new MemberCursor(last.getCreatedAt(), last.getId())));
  }

  @Transactional(readOnly = true)
  public CursorPage<AdminReservationResponse> getAdminReservationsByCursor(
      Long popupId, LocalDate scheduleDate, ReservationStatus status, String cursor, int size) {
    int limit = safeSize(size);
    List<Reservation> rows =
        reservationRepository.findAdminReservationsAfter(
            popupId, scheduleDate, status, ReservationCursor.decodeAdmin(cursor), limit + 1);
    return toPage(
        rows,
        limit,
        AdminReservationResponse::from,
        last ->
            ReservationCursor.encode(
                new AdminCursor(last.getScheduleDate(), last.getStartTime(), last.getId())));
  }

  private static <T> CursorPage<T> toPage(
      List<Reservation> rows,
      int limit,
      Function<Reservation, T> mapper,
      Function<Reservation, String> cursorOf) {
    boolean hasMore = rows.size() > limit;
    List<Reservation> page = hasMore ? rows.subList(0, limit) : rows;
    String next = hasMore ? cursorOf.apply(page.get(page.size() - 1)) : null;
    return new CursorPage<>(page.stream().map(mapper).toList(), next, hasMore);
  }

  private static int safePage(int page) {
    return Math.max(page, 0);
  }

  private static int safeSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }
}
