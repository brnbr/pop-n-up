package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.ScheduleAndPersonCount;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationCancelManager {

  private static final List<ReservationStatus> RESTORABLE_STATUSES =
      List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

  private final ScheduleRepository scheduleRepository;
  private final ReservationRepository reservationRepository;
  private final ScheduleCapacityCache scheduleCapacityCache;
  private final ReservationStatusReader reservationStatusReader;

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

  @Transactional
  public boolean expireNoShow(Long reservationId) {
    return processTerminalStatusChange(
        reservationId, ReservationStatus.EXPIRED, List.of(ReservationStatus.CONFIRMED));
  }

  private boolean processTerminalStatusChange(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses) {

    ScheduleAndPersonCount target =
        reservationRepository
            .findScheduleAndPersonCount(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    int updatedRows = reservationRepository.tryUpdateStatus(reservationId, newStatus, fromStatuses);

    if (updatedRows == 0) {
      return handleFailedTransition(reservationId, fromStatuses);
    }

    int restoredRows =
        scheduleRepository.tryDecreaseCapacity(target.scheduleId(), target.personCount());

    if (restoredRows == 0) {
      log.error(
          "[processTerminalStatusChange] 좌석 복구 실패 - 정합성 이상 가능성. "
              + "reservationId={}, scheduleId={}, personCount={}, newStatus={}",
          reservationId,
          target.scheduleId(),
          target.personCount(),
          newStatus);
      throw ScheduleErrorCode.INVALID_CANCEL_COUNT.toException();
    }

    scheduleCapacityCache.release(target.scheduleId(), target.personCount());
    return true;
  }

  private boolean handleFailedTransition(Long reservationId, List<ReservationStatus> fromStatuses) {

    ReservationStatus current =
        reservationStatusReader
            .getFreshStatus(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (current == ReservationStatus.CANCELED || current == ReservationStatus.EXPIRED) {
      return false; // 이미 다른 워커가 처리함 - 정상적인 no-op, 에러 아님
    }

    if (current == ReservationStatus.USED) {
      throw ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException();
    }

    if (!fromStatuses.contains(current)) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }

    throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
  }
}
