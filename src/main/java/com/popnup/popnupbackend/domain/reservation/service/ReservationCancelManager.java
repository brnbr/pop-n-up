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

  @Transactional
  public void cancel(Long reservationId) {
    processTerminalStatusChange(reservationId, ReservationStatus.CANCELED, RESTORABLE_STATUSES);
  }

  @Transactional
  public void expirePaymentTimeout(Long reservationId) {
    processTerminalStatusChange(
        reservationId, ReservationStatus.EXPIRED, List.of(ReservationStatus.PENDING));
  }

  @Transactional
  public void expireNoShow(Long reservationId) {
    processTerminalStatusChange(
        reservationId, ReservationStatus.EXPIRED, List.of(ReservationStatus.CONFIRMED));
  }

  private void processTerminalStatusChange(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses) {

    ScheduleAndPersonCount target =
        reservationRepository
            .findScheduleAndPersonCount(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    int updatedRows = reservationRepository.tryUpdateStatus(reservationId, newStatus, fromStatuses);

    if (updatedRows == 0) {
      handleFailedTransition(reservationId, fromStatuses);
      return;
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

    // DB 복구가 성공했으므로 Redis 카운터도 동기화한다.
    // 트랜잭션이 실제로 커밋된 뒤에만 실행되도록 지연시켜, 이후 롤백 시 Redis만 줄어든 채 남는
    // 드리프트를 방지한다.
    scheduleCapacityCache.release(target.scheduleId(), target.personCount());
  }

  private void handleFailedTransition(Long reservationId, List<ReservationStatus> fromStatuses) {
    ReservationStatus current =
        reservationRepository
            .findStatusById(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (current == ReservationStatus.CANCELED || current == ReservationStatus.EXPIRED) {
      return;
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
