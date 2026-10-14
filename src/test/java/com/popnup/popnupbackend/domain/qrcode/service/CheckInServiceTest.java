package com.popnup.popnupbackend.domain.qrcode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CheckInServiceTest {

  @InjectMocks private CheckInService checkInService;

  @Mock private ReservationRepository reservationRepository;

  private CheckInRequest request(String reservationNumber) {
    CheckInRequest request = new CheckInRequest();
    ReflectionTestUtils.setField(request, "reservationNumber", reservationNumber);
    return request;
  }

  private ServiceException failure(String reservationNumber) {
    return (ServiceException)
        org.assertj.core.api.Assertions.catchThrowable(
            () -> checkInService.checkIn(request(reservationNumber)));
  }

  @Test
  @DisplayName("성공: 비관적 락으로 조회된 예약에 checkIn을 호출하고 응답 DTO를 반환한다")
  void checkIn_success() {
    String reservationNumber = "R20260915TEST";
    Member member = mock(Member.class);
    given(member.getName()).willReturn("홍길동");

    Schedule schedule = mock(Schedule.class);
    given(schedule.isCheckInAvailable(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
        .willReturn(true);

    Reservation reservation = mock(Reservation.class);
    given(reservation.getId()).willReturn(100L);
    given(reservation.getReservationNumber()).willReturn(reservationNumber);
    given(reservation.getMember()).willReturn(member);
    given(reservation.getPersonCount()).willReturn(2);
    given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
    given(reservation.getSchedule()).willReturn(schedule);

    given(reservationRepository.findByReservationNumberWithPessimisticLock(reservationNumber))
        .willReturn(Optional.of(reservation));

    CheckInResponse response = checkInService.checkIn(request(reservationNumber));

    verify(reservation).checkIn();
    assertThat(response.getReservationId()).isEqualTo(100L);
    assertThat(response.getReservationNumber()).isEqualTo(reservationNumber);
    assertThat(response.getMemberName()).isEqualTo("홍길동");
    assertThat(response.getPersonCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("실패: 예약 번호에 해당하는 예약이 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
  void checkIn_notFound() {
    given(reservationRepository.findByReservationNumberWithPessimisticLock("R-NONE"))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> checkInService.checkIn(request("R-NONE")))
        .isInstanceOf(ServiceException.class)
        .satisfies(
            e ->
                assertThat(((ServiceException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));
  }

  @Test
  @DisplayName("R4 실패: 체크인 가능한 시간이 아니면(어제 QR, 종료된 회차) 체크인할 수 없다")
  void checkIn_outsideWindow_rejected() {
    Schedule schedule = mock(Schedule.class);
    given(schedule.isCheckInAvailable(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
        .willReturn(false);
    Reservation reservation = mock(Reservation.class);
    given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
    given(reservation.getSchedule()).willReturn(schedule);
    given(reservationRepository.findByReservationNumberWithPessimisticLock("R-OLD"))
        .willReturn(Optional.of(reservation));

    ServiceException e = failure("R-OLD");

    assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.CHECK_IN_NOT_AVAILABLE_TIME);
    verify(reservation, never()).checkIn();
  }

  @Test
  @DisplayName("R4: 이미 사용한 예약은 시간 검사보다 상태 오류가 먼저 나오도록 시간을 검사하지 않는다")
  void checkIn_alreadyUsed_statusErrorFirst() {
    Reservation reservation = mock(Reservation.class);
    given(reservation.getStatus()).willReturn(ReservationStatus.USED);
    given(reservationRepository.findByReservationNumberWithPessimisticLock("R-USED"))
        .willReturn(Optional.of(reservation));
    doThrow(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException())
        .when(reservation)
        .checkIn();

    ServiceException e = failure("R-USED");

    assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION);
    verify(reservation, never()).getSchedule();
  }
}
