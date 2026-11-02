package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.outbox.SeatReleaseOutbox;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class ReservationCancelManager {

  private static final List<ReservationStatus> RESTORABLE_STATUSES =
      List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

  private final ScheduleRepository scheduleRepository;
  private final ReservationRepository reservationRepository;
  private final SeatReleaseOutbox seatReleaseOutbox;

  public ReservationCancelManager(
      ScheduleRepository scheduleRepository,
      ReservationRepository reservationRepository,
      SeatReleaseOutbox seatReleaseOutbox) {
    this.scheduleRepository = scheduleRepository;
    this.reservationRepository = reservationRepository;
    this.seatReleaseOutbox = seatReleaseOutbox;
  }

  @Transactional
  public boolean cancel(Long reservationId) {
    return processTerminalStatusChange(
        reservationId, ReservationStatus.CANCELED, RESTORABLE_STATUSES);
  }

  @Transactional
  public boolean expirePaymentTimeout(Long reservationId) {
    return processTerminalStatusChange(
        reservationId, ReservationStatus.EXPIRED, List.of(ReservationStatus.PENDING));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int expirePaymentTimeoutChunk(List<Reservation> lockedTargets) {
    Map<Long, Integer> seatsBySchedule =
        lockedTargets.stream()
            .collect(
                Collectors.groupingBy(
                    r -> r.getSchedule().getId(),
                    TreeMap::new,
                    Collectors.summingInt(Reservation::getPersonCount)));
    restoreSeats(seatsBySchedule);
    seatReleaseOutbox.record(seatsBySchedule);

    List<Long> ids = lockedTargets.stream().map(Reservation::getId).toList();
    long expiredRows = reservationRepository.expirePendingByIds(ids);
    if (expiredRows != ids.size()) {
      log.error(
          "[expirePaymentTimeoutChunk] 잠근 예약 중 결제 대기가 아닌 예약이 있음 targets={}, expired={}",
          ids.size(),
          expiredRows);
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }
    return ids.size();
  }

  private void restoreSeats(Map<Long, Integer> seatsBySchedule) {
    int restored = scheduleRepository.tryDecreaseCapacities(seatsBySchedule);
    if (restored != seatsBySchedule.size()) {
      log.error(
          "[expirePaymentTimeoutChunk] 좌석 복구 실패 seatsBySchedule={}, restored={}",
          seatsBySchedule,
          restored);
      throw ScheduleErrorCode.INVALID_CANCEL_COUNT.toException();
    }
  }

  private boolean processTerminalStatusChange(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses) {

    Reservation reservation =
        reservationRepository
            .findByIdWithPessimisticLock(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (!fromStatuses.contains(reservation.getStatus())) {
      return handleFailedTransition(reservation.getStatus());
    }

    Long scheduleId = reservation.getSchedule().getId();
    int personCount = reservation.getPersonCount();

    if (scheduleRepository.tryDecreaseCapacity(scheduleId, personCount) == 0) {
      log.error(
          "[processTerminalStatusChange] 좌석 복구 실패 "
              + "reservationId={}, scheduleId={}, personCount={}, newStatus={}",
          reservationId,
          scheduleId,
          personCount,
          newStatus);
      throw ScheduleErrorCode.INVALID_CANCEL_COUNT.toException();
    }

    if (reservationRepository.tryUpdateStatus(reservationId, newStatus, fromStatuses) == 0) {
      log.error(
          "[processTerminalStatusChange] 잠근 예약의 상태 변경 실패 reservationId={}, newStatus={}",
          reservationId,
          newStatus);
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }

    seatReleaseOutbox.record(scheduleId, personCount);
    return true;
  }

  private boolean handleFailedTransition(ReservationStatus current) {
    if (current.isEnded()) {
      return false;
    }

    if (current == ReservationStatus.USED) {
      throw ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException();
    }

    throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
  }
}
