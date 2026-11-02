package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.reservation.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

  @InjectMocks private ReservationService reservationService;

  @Mock private ReservationRepository reservationRepository;
  @Mock private QrService qrService;
  @Mock private ReservationCancelManager reservationCancelManager;
  @Mock private PlatformTransactionManager transactionManager;

  private void assertErrorCode(Runnable action, ReservationErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ServiceException.class)
        .satisfies(e -> assertThat(((ServiceException) e).getErrorCode()).isEqualTo(expected));
  }

  @Nested
  @DisplayName("예약 확정 [confirmReservation]")
  class ConfirmReservationTest {

    @Test
    @DisplayName("성공: 비관적 락으로 조회된 예약의 confirm을 호출한다")
    void confirmReservation_success() {
      // given
      Long reservationId = 100L;
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findByIdWithPessimisticLock(reservationId))
          .willReturn(Optional.of(reservation));

      // when
      reservationService.confirmReservation(reservationId);

      // then
      verify(reservation).confirm();
    }
  }

  @Nested
  @DisplayName("체크인 [checkIn]")
  class CheckInTest {

    private CheckInRequest request(String reservationNumber) {
      CheckInRequest request = new CheckInRequest();
      ReflectionTestUtils.setField(request, "reservationNumber", reservationNumber);
      return request;
    }

    @Test
    @DisplayName("성공: 비관적 락으로 조회된 예약에 현재 시각으로 checkIn을 호출하고 응답 DTO를 반환한다")
    void checkIn_success() {
      // given
      String reservationNumber = "R20260915TEST";
      Member member = mock(Member.class);
      given(member.getName()).willReturn("홍길동");

      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(100L);
      given(reservation.getReservationNumber()).willReturn(reservationNumber);
      given(reservation.getMember()).willReturn(member);
      given(reservation.getPersonCount()).willReturn(2);
      given(reservationRepository.findByReservationNumberWithPessimisticLock(reservationNumber))
          .willReturn(Optional.of(reservation));

      // when
      CheckInResponse response = reservationService.checkIn(request(reservationNumber));

      // then
      verify(reservation).checkIn(any(LocalDateTime.class));
      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo(reservationNumber);
      assertThat(response.getMemberName()).isEqualTo("홍길동");
      assertThat(response.getPersonCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("동적 QR 코드 조회 [getReservationQrCode]")
  class GetReservationQrCodeTest {

    @Test
    @DisplayName("성공: 본인 확인과 QR 발급 가능 여부를 검증한 뒤 QR 바이트 배열을 반환한다")
    void getReservationQrCode_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;
      String reservationNumber = "R20260915TEST";
      byte[] qrBytes = new byte[] {0x1, 0x2};

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getReservationNumber()).willReturn(reservationNumber);
      given(qrService.generateQrCodeImage(reservationNumber)).willReturn(qrBytes);

      // when
      byte[] result = reservationService.getReservationQrCode(memberId, reservationId);

      // then
      assertThat(result).isEqualTo(qrBytes);
      verify(reservation).validateOwnedBy(memberId);
      verify(reservation).validateQrIssuable();
    }

    @Test
    @DisplayName("실패: 본인의 예약이 아니면 UNAUTHORIZED_RESERVATION_ACCESS이고 QR을 만들지 않는다")
    void getReservationQrCode_unauthorized() {
      // given
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));
      doThrow(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException())
          .when(reservation)
          .validateOwnedBy(1L);

      // when & then
      assertErrorCode(
          () -> reservationService.getReservationQrCode(1L, 100L),
          ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
      verify(qrService, never()).generateQrCodeImage(any());
    }

    @Test
    @DisplayName("실패: QR을 발급할 수 없는 상태면 INVALID_RESERVATION_STATUS이고 QR을 만들지 않는다")
    void getReservationQrCode_invalidStatus() {
      // given
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));
      doThrow(ReservationErrorCode.INVALID_RESERVATION_STATUS.toException())
          .when(reservation)
          .validateQrIssuable();

      // when & then
      assertErrorCode(
          () -> reservationService.getReservationQrCode(1L, 100L),
          ReservationErrorCode.INVALID_RESERVATION_STATUS);
      verify(qrService, never()).generateQrCodeImage(any());
    }
  }

  @Nested
  @DisplayName("예약 취소 [cancel]")
  class CancelTest {

    private Reservation lockedReservation() {
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findByIdWithPessimisticLock(100L))
          .willReturn(Optional.of(reservation));
      return reservation;
    }

    @Test
    @DisplayName("성공: 락을 잡은 예약의 본인 확인과 취소 가능 여부를 검증한 뒤 취소 처리를 위임한다")
    void cancel_validatesThenDelegates() {
      Reservation reservation = lockedReservation();

      reservationService.cancel(1L, 100L);

      InOrder order = inOrder(reservation, reservationCancelManager);
      order.verify(reservation).validateOwnedBy(1L);
      order.verify(reservation).validateCancelable(any(LocalDateTime.class));
      order.verify(reservationCancelManager).cancel(100L);
    }

    @Test
    @DisplayName("실패: 본인 예약이 아니면 UNAUTHORIZED_RESERVATION_ACCESS이고 취소하지 않는다")
    void cancel_unauthorized() {
      Reservation reservation = lockedReservation();
      doThrow(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException())
          .when(reservation)
          .validateOwnedBy(1L);

      assertErrorCode(
          () -> reservationService.cancel(1L, 100L),
          ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
      verify(reservationCancelManager, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("실패: 취소할 수 없는 예약(시작된 결제 완료 예약)이면 CANCEL_NOT_ALLOWED_AFTER_START이고 취소하지 않는다")
    void cancel_notCancelable() {
      Reservation reservation = lockedReservation();
      doThrow(ReservationErrorCode.CANCEL_NOT_ALLOWED_AFTER_START.toException())
          .when(reservation)
          .validateCancelable(any(LocalDateTime.class));

      assertErrorCode(
          () -> reservationService.cancel(1L, 100L),
          ReservationErrorCode.CANCEL_NOT_ALLOWED_AFTER_START);
      verify(reservationCancelManager, never()).cancel(anyLong());
    }
  }

  @Nested
  @DisplayName("예약 목록 및 단건 조회")
  class QueryTest {

    @Test
    @DisplayName("내 예약 목록: 음수 page는 0으로, 너무 큰 size는 100으로, 0 이하 size는 1로 보정한다")
    void getMemberReservations_pageParamsClamped() {
      given(reservationRepository.findMemberReservations(1L, 0, 100)).willReturn(List.of());
      given(reservationRepository.findMemberReservations(1L, 3, 1)).willReturn(List.of());

      reservationService.getMemberReservations(1L, -5, 100000);
      reservationService.getMemberReservations(1L, 3, 0);

      verify(reservationRepository).findMemberReservations(1L, 0, 100);
      verify(reservationRepository).findMemberReservations(1L, 3, 1);
    }

    @Test
    @DisplayName("실패: 단건 조회 시 예약이 없으면 RESERVATION_NOT_FOUND")
    void getMemberReservation_notFound() {
      given(reservationRepository.findById(100L)).willReturn(Optional.empty());

      assertErrorCode(
          () -> reservationService.getMemberReservation(1L, 100L),
          ReservationErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("실패: 단건 조회 시 남의 예약이면 UNAUTHORIZED_RESERVATION_ACCESS (QR·취소와 같은 403)")
    void getMemberReservation_otherMember_forbidden() {
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));
      doThrow(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException())
          .when(reservation)
          .validateOwnedBy(1L);

      assertErrorCode(
          () -> reservationService.getMemberReservation(1L, 100L),
          ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
    }
  }
}
