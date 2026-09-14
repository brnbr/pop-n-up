package com.popnup.popnupbackend.domain.reservation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
    @DisplayName("타임아웃 대상이 없으면 만료 처리를 호출하지 않는다")
    void empty() {
      given(reservationRepository.findByStatusAndCreatedAtBefore(any(), any()))
          .willReturn(List.of());

      log.info("[payTimeOut.empty] input(targetCount=0)");

      reservationTimeoutProcessor.payTimeOut();

      verify(reservationCancelManager, never()).expirePaymentTimeout(any());
    }

    @Test
    @DisplayName("타임아웃 대상이 있으면 각 예약의 결제 타임아웃 만료를 시도한다")
    void withTargets() {
      given(reservationRepository.findByStatusAndCreatedAtBefore(any(), any()))
          .willReturn(List.of(pendingReservation));

      log.info("[payTimeOut.withTargets] input(targetCount=1, targetId=10)");

      reservationTimeoutProcessor.payTimeOut();

      verify(reservationCancelManager, times(1)).expirePaymentTimeout(10L);
    }

    @Test
    @DisplayName("단건 처리 중 예외가 발생해도 나머지 예약 처리를 계속한다")
    void continuesOnSingleFailure() {
      Reservation another = Reservation.createReservation("R2", member, schedule, 1);

      ReflectionTestUtils.setField(another, "id", 11L);

      given(reservationRepository.findByStatusAndCreatedAtBefore(any(), any()))
          .willReturn(List.of(pendingReservation, another));

      doThrow(new RuntimeException("DB 오류"))
          .when(reservationCancelManager)
          .expirePaymentTimeout(10L);

      log.info("[payTimeOut.continuesOnSingleFailure] " + "input(targetIds=[10(실패유도), 11])");

      reservationTimeoutProcessor.payTimeOut();

      verify(reservationCancelManager, times(1)).expirePaymentTimeout(10L);

      verify(reservationCancelManager, times(1)).expirePaymentTimeout(11L);
    }
  }

  @Nested
  @DisplayName("expireSingleTimeoutReservation 검증")
  class ExpireSingleTimeoutReservation {

    @Test
    @DisplayName("결제 타임아웃 처리를 CancelManager에 위임한다")
    void delegatesToCancelManager() {
      log.info("[expireSingleTimeoutReservation] " + "input(reservationId=10)");

      reservationTimeoutProcessor.expireSingleTimeoutReservation(10L);

      verify(reservationCancelManager, times(1)).expirePaymentTimeout(10L);
    }
  }
}
