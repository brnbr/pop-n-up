package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ReservationTimeoutProcessorTest {

  @Mock private ReservationRepository reservationRepository;
  @Mock private ReservationCancelManager reservationCancelManager;

  @InjectMocks private ReservationTimeoutProcessor reservationTimeoutProcessor;

  private Reservation pendingReservation;
  private Member member;
  private Schedule schedule;

  @BeforeEach
  void setUp() {
    member = Member.createLocal("test@test.com", "pw", "테스터");
    ReflectionTestUtils.setField(member, "id", 1L);

    Popup popup =
        Popup.builder()
            .title("테스트 팝업")
            .category(PopupCategory.ETC)
            .region("서울")
            .address("서울시 강남구")
            .startDate(LocalDate.now().minusDays(10))
            .endDate(LocalDate.now().plusDays(10))
            .isFree(true)
            .price(0)
            .status(PopupStatus.OPEN)
            .build();

    schedule =
        Schedule.createSchedule(
            popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
    ReflectionTestUtils.setField(schedule, "id", 100L);

    pendingReservation = Reservation.createReservation("R1", member, schedule, 2);
    ReflectionTestUtils.setField(pendingReservation, "id", 10L);
  }

  @Nested
  @DisplayName("payTimeOut 검증")
  class PayTimeOut {

    @Test
    @DisplayName("타임아웃 대상이 없으면 아무것도 처리하지 않는다")
    void empty() {
      given(reservationRepository.findByStatusAndCreatedAtBefore(any(), any()))
          .willReturn(List.of());

      log.info("[payTimeOut.empty] input(targetCount=0)");

      reservationTimeoutProcessor.payTimeOut();

      log.info("[payTimeOut.empty] verify reservationCancelManager.expire never called");

      verify(reservationCancelManager, never()).expire(any());
    }

    @Test
    @DisplayName("타임아웃 대상이 있으면 단건 만료 처리를 각각 시도한다")
    void withTargets() {
      given(reservationRepository.findByStatusAndCreatedAtBefore(any(), any()))
          .willReturn(List.of(pendingReservation));
      given(reservationRepository.findById(10L)).willReturn(Optional.of(pendingReservation));

      log.info("[payTimeOut.withTargets] input(targetCount=1, targetId=10)");

      reservationTimeoutProcessor.payTimeOut();

      log.info("[payTimeOut.withTargets] verify reservationCancelManager.expire(10L) called");

      verify(reservationCancelManager, times(1)).expire(10L);
    }

    @Test
    @DisplayName("단건 처리 중 예외가 발생해도 나머지 건 처리에 영향을 주지 않는다")
    void continuesOnSingleFailure() {
      Reservation another = Reservation.createReservation("R2", member, schedule, 1);
      ReflectionTestUtils.setField(another, "id", 11L);

      given(reservationRepository.findByStatusAndCreatedAtBefore(any(), any()))
          .willReturn(List.of(pendingReservation, another));
      given(reservationRepository.findById(10L)).willReturn(Optional.of(pendingReservation));
      given(reservationRepository.findById(11L)).willReturn(Optional.of(another));
      Mockito.doThrow(new RuntimeException("DB 오류")).when(reservationCancelManager).expire(10L);

      log.info("[payTimeOut.continuesOnSingleFailure] input(targetIds=[10(실패유도), 11])");

      reservationTimeoutProcessor.payTimeOut();

      log.info(
          "[payTimeOut.continuesOnSingleFailure] verify expire(10L) and expire(11L) both attempted");

      verify(reservationCancelManager, times(1)).expire(10L);
      verify(reservationCancelManager, times(1)).expire(11L);
    }
  }

  @Nested
  @DisplayName("expireSingleTimeoutReservation 검증")
  class ExpireSingleTimeoutReservation {

    @Test
    @DisplayName("PENDING 상태면 cancelManager.expire를 호출한다")
    void pendingStatus() {
      given(reservationRepository.findById(10L)).willReturn(Optional.of(pendingReservation));

      log.info(
          "[expireSingleTimeoutReservation.pendingStatus] input(reservationId=10, status=PENDING)");

      reservationTimeoutProcessor.expireSingleTimeoutReservation(10L);

      log.info("[expireSingleTimeoutReservation.pendingStatus] verify expire(10L) called");

      verify(reservationCancelManager, times(1)).expire(10L);
    }

    @Test
    @DisplayName("이미 PENDING이 아니면(결제 완료됨) expire를 호출하지 않는다")
    void notPendingAnymore() {
      pendingReservation.confirm(true);
      given(reservationRepository.findById(10L)).willReturn(Optional.of(pendingReservation));

      log.info(
          "[expireSingleTimeoutReservation.notPendingAnymore] input(reservationId=10, status=CONFIRMED)");

      reservationTimeoutProcessor.expireSingleTimeoutReservation(10L);

      log.info(
          "[expireSingleTimeoutReservation.notPendingAnymore] verify expire never called - 결제 완료 건은 만료시키면 안 됨");

      verify(reservationCancelManager, never()).expire(any());
    }

    @Test
    @DisplayName("예약이 존재하지 않으면 예외 발생")
    void notFound() {
      given(reservationRepository.findById(999L)).willReturn(Optional.empty());

      log.info("[expireSingleTimeoutReservation.notFound] input(reservationId=999)");

      ServiceException exception =
          Assertions.assertThrows(
              ServiceException.class,
              () -> reservationTimeoutProcessor.expireSingleTimeoutReservation(999L));

      log.info(
          "[expireSingleTimeoutReservation.notFound] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.RESERVATION_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }
  }
}
