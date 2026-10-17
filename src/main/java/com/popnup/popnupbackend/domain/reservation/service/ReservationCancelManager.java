package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.ScheduleAndPersonCount;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class ReservationCancelManager {

  private static final List<ReservationStatus> RESTORABLE_STATUSES =
      List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

  private final ScheduleRepository scheduleRepository;
  private final ReservationRepository reservationRepository;
  private final ScheduleCapacityCache scheduleCapacityCache;
  private final TransactionTemplate freshReadTransaction;

  public ReservationCancelManager(
      ScheduleRepository scheduleRepository,
      ReservationRepository reservationRepository,
      ScheduleCapacityCache scheduleCapacityCache,
      PlatformTransactionManager transactionManager) {
    this.scheduleRepository = scheduleRepository;
    this.reservationRepository = reservationRepository;
    this.scheduleCapacityCache = scheduleCapacityCache;
    this.freshReadTransaction = new TransactionTemplate(transactionManager);
    this.freshReadTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

  private boolean processTerminalStatusChange(
      Long reservationId, ReservationStatus newStatus, List<ReservationStatus> fromStatuses) {

    ScheduleAndPersonCount target =
        reservationRepository
            .findScheduleAndPersonCount(reservationId)
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    int updatedRows = reservationRepository.tryUpdateStatus(reservationId, newStatus, fromStatuses);

    if (updatedRows == 0) {
      return handleFailedTransition(reservationId);
    }

    int restoredRows =
        scheduleRepository.tryDecreaseCapacity(target.scheduleId(), target.personCount());

    if (restoredRows == 0) {
      log.error(
          "[processTerminalStatusChange] 좌석 복구 실패 "
              + "reservationId={}, scheduleId={}, personCount={}, newStatus={}",
          reservationId,
          target.scheduleId(),
          target.personCount(),
          newStatus);
      throw ScheduleErrorCode.INVALID_CANCEL_COUNT.toException();
    }

    scheduleCapacityCache.releaseAfterCommit(target.scheduleId(), target.personCount());
    return true;
  }

  private boolean handleFailedTransition(Long reservationId) {

    ReservationStatus current =
        freshReadTransaction
            .execute(status -> reservationRepository.findStatusById(reservationId))
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    if (current.isEnded()) {
      return false;
    }

    if (current == ReservationStatus.USED) {
      throw ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException();
    }

    throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
  }
}
