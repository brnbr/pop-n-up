package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.ScheduleAndPersonCount;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

class ReservationCancelManagerTest {

  private static final Long RESERVATION_ID = 1L;
  private static final Long SCHEDULE_ID = 10L;
  private static final int PERSON_COUNT = 3;

  private ScheduleRepository scheduleRepository;
  private ReservationRepository reservationRepository;
  private ScheduleCapacityCache scheduleCapacityCache;
  private PlatformTransactionManager transactionManager;
  private ReservationCancelManager manager;

  @BeforeEach
  void setUp() {
    scheduleRepository = mock(ScheduleRepository.class);
    reservationRepository = mock(ReservationRepository.class);
    scheduleCapacityCache = mock(ScheduleCapacityCache.class);
    transactionManager = mock(PlatformTransactionManager.class);
    manager =
        new ReservationCancelManager(
            scheduleRepository, reservationRepository, scheduleCapacityCache, transactionManager);
    given(reservationRepository.findScheduleAndPersonCount(RESERVATION_ID))
        .willReturn(Optional.of(new ScheduleAndPersonCount(SCHEDULE_ID, PERSON_COUNT)));
  }

  private void updateResult(ReservationStatus newStatus, List<ReservationStatus> from, int rows) {
    given(reservationRepository.tryUpdateStatus(RESERVATION_ID, newStatus, from)).willReturn(rows);
  }

  private void freshStatusIs(ReservationStatus status) {
    given(reservationRepository.findStatusById(RESERVATION_ID)).willReturn(Optional.of(status));
  }

  private void assertErrorCode(Runnable action, Object expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ServiceException.class)
        .satisfies(e -> assertThat(((ServiceException) e).getErrorCode()).isEqualTo(expected));
  }

  @Test
  @DisplayName("취소 성공: PENDING/CONFIRMED에서 CANCELED로 바꾸고 좌석과 Redis 카운터를 되돌린다")
  void cancel_success() {
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        1);
    given(scheduleRepository.tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT)).willReturn(1);

    boolean result = manager.cancel(RESERVATION_ID);

    assertThat(result).isTrue();
    verify(scheduleRepository).tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT);
    verify(scheduleCapacityCache).releaseAfterCommit(SCHEDULE_ID, PERSON_COUNT);
  }

  @Test
  @DisplayName("결제 만료: PENDING에서 EXPIRED로만 바꾼다")
  void expirePaymentTimeout_onlyFromPending() {
    updateResult(ReservationStatus.EXPIRED, List.of(ReservationStatus.PENDING), 1);
    given(scheduleRepository.tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT)).willReturn(1);

    assertThat(manager.expirePaymentTimeout(RESERVATION_ID)).isTrue();

    verify(scheduleCapacityCache).releaseAfterCommit(SCHEDULE_ID, PERSON_COUNT);
  }

  @Test
  @DisplayName("예약이 없으면 RESERVATION_NOT_FOUND이고 상태 변경을 시도하지 않는다")
  void reservationMissing_notFound() {
    given(reservationRepository.findScheduleAndPersonCount(RESERVATION_ID))
        .willReturn(Optional.empty());

    assertErrorCode(
        () -> manager.cancel(RESERVATION_ID), ReservationErrorCode.RESERVATION_NOT_FOUND);

    verify(reservationRepository, never()).tryUpdateStatus(anyLong(), any(), any());
  }

  @Test
  @DisplayName("좌석 복구가 0건이면 INVALID_CANCEL_COUNT이고 Redis 카운터는 건드리지 않는다")
  void restoreFails_invalidCancelCount() {
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        1);
    given(scheduleRepository.tryDecreaseCapacity(SCHEDULE_ID, PERSON_COUNT)).willReturn(0);

    assertErrorCode(() -> manager.cancel(RESERVATION_ID), ScheduleErrorCode.INVALID_CANCEL_COUNT);

    verify(scheduleCapacityCache, never()).releaseAfterCommit(anyLong(), anyInt());
  }

  @ParameterizedTest
  @EnumSource(
      value = ReservationStatus.class,
      names = {"CANCELED", "EXPIRED"})
  @DisplayName("상태 변경이 0건이고 이미 CANCELED/EXPIRED면 false를 반환하고 좌석은 건드리지 않는다")
  void alreadyTerminal_returnsFalse(ReservationStatus current) {
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        0);
    freshStatusIs(current);

    assertThat(manager.cancel(RESERVATION_ID)).isFalse();

    verify(scheduleRepository, never()).tryDecreaseCapacity(anyLong(), anyInt());
    verify(scheduleCapacityCache, never()).releaseAfterCommit(anyLong(), anyInt());
  }

  @Test
  @DisplayName("상태 변경이 0건이고 이미 USED면 ALREADY_PROCESSED_RESERVATION")
  void alreadyUsed_alreadyProcessed() {
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        0);
    freshStatusIs(ReservationStatus.USED);

    assertErrorCode(
        () -> manager.cancel(RESERVATION_ID), ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
  }

  @Test
  @DisplayName("상태 변경이 0건이고 허용 상태가 아니면 INVALID_RESERVATION_STATUS (결제 만료는 PENDING만 허용)")
  void failedAndNotAllowedStatus_invalidStatus() {
    updateResult(ReservationStatus.EXPIRED, List.of(ReservationStatus.PENDING), 0);
    freshStatusIs(ReservationStatus.CONFIRMED);

    assertErrorCode(
        () -> manager.expirePaymentTimeout(RESERVATION_ID),
        ReservationErrorCode.INVALID_RESERVATION_STATUS);
  }

  @Test
  @DisplayName("상태 변경이 0건인데 최신 상태를 읽을 수 없으면 RESERVATION_NOT_FOUND")
  void failedAndStatusMissing_notFound() {
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        0);
    given(reservationRepository.findStatusById(RESERVATION_ID)).willReturn(Optional.empty());

    assertErrorCode(
        () -> manager.cancel(RESERVATION_ID), ReservationErrorCode.RESERVATION_NOT_FOUND);
  }

  @Test
  @DisplayName("상태 변경이 0건일 때 최신 상태는 새 트랜잭션(REQUIRES_NEW)에서 읽는다")
  void freshStatus_readInNewTransaction() {
    updateResult(
        ReservationStatus.CANCELED,
        List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
        0);
    freshStatusIs(ReservationStatus.CANCELED);

    manager.cancel(RESERVATION_ID);

    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }
}
