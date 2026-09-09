package com.popnup.popnupbackend.domain.reservation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationTimeoutProcessorTest {

  @InjectMocks private ReservationTimeoutProcessor processor;
  @Mock private ReservationRepository reservationRepository;
  @Mock private ReservationCancelManager cancelManager;

  @Test
  @DisplayName("payTimeOut: 만료 대상 예약 루프 중 한 건이 실패해도 다른 건은 계속 처리된다")
  void payTimeOut_BatchLoopExceptionIsolation() {
    Reservation r1 = Reservation.createReservation("R001", null, null, 1);
    ReflectionTestUtils.setField(r1, "id", 101L);
    Reservation r2 = Reservation.createReservation("R002", null, null, 1);
    ReflectionTestUtils.setField(r2, "id", 102L);

    given(
            reservationRepository.findByStatusAndCreatedAtBefore(
                eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
        .willReturn(List.of(r1, r2));

    // r1 처리 시 예외 발생, r2는 정상 조회
    given(reservationRepository.findById(101L)).willThrow(new RuntimeException("DB Timeout"));
    given(reservationRepository.findById(102L)).willReturn(Optional.of(r2));

    processor.payTimeOut();

    // 102L 취소 매니저는 정상 실행되어야 함
    verify(cancelManager, times(1)).cancel(r2);
  }

  @Test
  @DisplayName("cancelSingleTimeoutReservation: 이미 결제 완료(CONFIRMED)된 건은 취소하지 않고 무시한다")
  void cancelSingleTimeoutReservation_SkipIfAlreadyConfirmed() {
    Reservation r = Reservation.createReservation("R001", null, null, 1);
    r.confirm();
    given(reservationRepository.findById(10L)).willReturn(Optional.of(r));

    processor.cancelSingleTimeoutReservation(10L);

    verify(cancelManager, never()).cancel(any());
  }
}
