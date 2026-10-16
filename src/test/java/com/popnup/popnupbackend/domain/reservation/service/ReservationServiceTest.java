package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.qrcode.service.QrService;
import com.popnup.popnupbackend.domain.reservation.dto.response.AdminReservationResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "jwt.secret=784dK3Hk+sSIkd37at/v1xMeOYLDaZviwulL2vHU0KvM5PK2cRAjbNMCodkD88gw7O6ueENnWQ94AG0WztDLCA=="
    })
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

  @InjectMocks private ReservationService reservationService;

  @Mock private ReservationRepository reservationRepository;
  @Mock private QrService qrService;
  @Mock private ReservationCancelManager reservationCancelManager;

  @Spy
  private TransactionTemplate transactionTemplate =
      new TransactionTemplate(mock(PlatformTransactionManager.class));

  @Nested
  @DisplayName("예약 확정 [confirmReservation]")
  class ConfirmReservationTest {

    @Test
    @DisplayName("성공: 비관적 락으로 조회된 예약에 결제 성공 여부를 전달하여 confirm을 호출한다")
    void confirmReservation_success() {
      // given
      Long reservationId = 100L;
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findByIdWithPessimisticLock(reservationId))
          .willReturn(Optional.of(reservation));

      // when
      reservationService.confirmReservation(reservationId, true);

      // then
      verify(reservation).confirm(true);
    }

    @Test
    @DisplayName("실패: 예약이 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void confirmReservation_notFound() {
      // given
      Long reservationId = 100L;
      given(reservationRepository.findByIdWithPessimisticLock(reservationId))
          .willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> reservationService.confirmReservation(reservationId, false))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }
  }

  @Nested
  @DisplayName("동적 QR 코드 조회 [getReservationQrCode]")
  class GetReservationQrCodeTest {

    @Test
    @DisplayName("성공: 본인의 CONFIRMED 예약인 경우 QR 바이트 배열을 반환한다")
    void getReservationQrCode_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;
      String reservationNumber = "R20260915TEST";
      byte[] qrBytes = new byte[] {0x1, 0x2};

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getReservationNumber()).willReturn(reservationNumber);
      given(qrService.generateQrCodeImage(reservationNumber)).willReturn(qrBytes);

      // when
      byte[] result = reservationService.getReservationQrCode(memberId, reservationId);

      // then
      assertThat(result).isEqualTo(qrBytes);
      verify(qrService).generateQrCodeImage(reservationNumber);
    }

    @Test
    @DisplayName("실패: 본인의 예약이 아니면 UNAUTHORIZED_RESERVATION_ACCESS 예외가 발생한다")
    void getReservationQrCode_unauthorized() {
      // given
      Long loginMemberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(loginMemberId)).willReturn(false);

      // when & then
      assertThatThrownBy(
              () -> reservationService.getReservationQrCode(loginMemberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS));
    }

    @Test
    @DisplayName("실패: 예약 상태가 CONFIRMED가 아니면 INVALID_RESERVATION_STATUS 예외가 발생한다")
    void getReservationQrCode_invalidStatus() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);
      given(reservation.getStatus()).willReturn(ReservationStatus.PENDING);

      // when & then
      assertThatThrownBy(() -> reservationService.getReservationQrCode(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS));
    }
  }

  @Nested
  @DisplayName("예약 취소 [cancel]")
  class CancelTest {

    @Test
    @DisplayName("성공: 본인 예약인 경우 ReservationCancelManager에게 취소 처리를 위임한다")
    void cancel_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);

      // when
      reservationService.cancel(memberId, reservationId);

      // then
      verify(reservationCancelManager).cancel(reservationId);
    }

    @Test
    @DisplayName("실패: 본인 예약이 아니면 UNAUTHORIZED_RESERVATION_ACCESS 예외가 발생한다")
    void cancel_unauthorized() {
      // given
      Long loginMemberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(loginMemberId)).willReturn(false);

      // when & then
      assertThatThrownBy(() -> reservationService.cancel(loginMemberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS));

      verify(reservationCancelManager, never()).cancel(anyLong());
    }
  }

  @Nested
  @DisplayName("예약 목록 및 단건 조회")
  class QueryTest {

    @Test
    @DisplayName("성공: 회원의 예약 목록 전체를 정상 조회한다")
    void getMemberReservations_success() {
      // given
      Long memberId = 1L;
      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(10L);
      given(reservation.getReservationNumber()).willReturn("R20260915TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.findMemberReservations(memberId, 0, 20))
          .willReturn(List.of(reservation));

      // when
      List<ReservationResponse> results = reservationService.getMemberReservations(memberId, 0, 20);

      // then
      assertThat(results).hasSize(1);
      assertThat(results.get(0).getReservationId()).isEqualTo(10L);
    }

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
    @DisplayName("성공: 예약 ID와 회원 ID로 단건 예약을 정상 조회한다")
    void getMemberReservation_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(reservationId);
      given(reservation.getReservationNumber()).willReturn("R20260915TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.findByIdAndMemberId(reservationId, memberId))
          .willReturn(Optional.of(reservation));

      // when
      ReservationResponse response =
          reservationService.getMemberReservation(memberId, reservationId);

      // then
      assertThat(response.getReservationId()).isEqualTo(reservationId);
      verify(reservationRepository).findByIdAndMemberId(reservationId, memberId);
    }

    @Test
    @DisplayName("실패: 단건 조회 시 내역이 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void getMemberReservation_notFound() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      given(reservationRepository.findByIdAndMemberId(reservationId, memberId))
          .willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> reservationService.getMemberReservation(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }
  }

  @Nested
  @DisplayName("관리자 예약 목록 조회 [getAdminReservations]")
  class GetAdminReservationsTest {

    @Test
    @DisplayName("성공: 조건에 맞는 관리자용 예약 목록을 반환한다")
    void getAdminReservations_success() {
      // given
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 15);
      ReservationStatus status = ReservationStatus.CONFIRMED;

      Reservation reservation = mock(Reservation.class);
      Schedule schedule = mock(Schedule.class);
      Member member = mock(Member.class);

      given(reservation.getId()).willReturn(100L);
      given(reservation.getReservationNumber()).willReturn("R20260915ADMIN");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);
      given(reservation.getSchedule()).willReturn(schedule);
      given(reservation.getMember()).willReturn(member);

      given(schedule.getPopup())
          .willReturn(mock(com.popnup.popnupbackend.domain.popup.entity.Popup.class));
      given(schedule.getScheduleDate()).willReturn(scheduleDate);
      given(schedule.getStartTime()).willReturn(java.time.LocalTime.of(10, 0));
      given(schedule.getEndTime()).willReturn(java.time.LocalTime.of(11, 0));

      given(member.getId()).willReturn(10L);
      given(member.getName()).willReturn("관리자확인");

      given(reservationRepository.findAdminReservations(popupId, scheduleDate, status, 0, 20))
          .willReturn(List.of(reservation));

      // when
      List<AdminReservationResponse> responses =
          reservationService.getAdminReservations(popupId, scheduleDate, status, 0, 20);

      // then
      assertThat(responses).hasSize(1);
      assertThat(responses.get(0).getReservationId()).isEqualTo(100L);
      assertThat(responses.get(0).getReservationNumber()).isEqualTo("R20260915ADMIN");
    }

    @Test
    @DisplayName("성공: size가 상한(100)을 넘거나 1 미만이고 page가 음수여도 허용 범위로 보정해 조회한다")
    void getAdminReservations_clampsPagingParams() {
      // given
      given(reservationRepository.findAdminReservations(1L, null, null, 0, 100))
          .willReturn(List.of());
      given(reservationRepository.findAdminReservations(1L, null, null, 3, 1))
          .willReturn(List.of());

      // when
      reservationService.getAdminReservations(1L, null, null, -5, 100000);
      reservationService.getAdminReservations(1L, null, null, 3, 0);

      // then
      verify(reservationRepository).findAdminReservations(1L, null, null, 0, 100);
      verify(reservationRepository).findAdminReservations(1L, null, null, 3, 1);
    }
  }

  @Nested
  @DisplayName("정책 검증 [R3 취소 시점]")
  class PolicyTest {

    private Reservation reservationWith(ReservationStatus status, Schedule schedule) {
      Reservation reservation = mock(Reservation.class);
      given(reservation.isOwnedBy(1L)).willReturn(true);
      given(reservation.getStatus()).willReturn(status);
      if (schedule != null) {
        given(reservation.getSchedule()).willReturn(schedule);
      }
      return reservation;
    }

    private ServiceException failure(Runnable action) {
      try {
        action.run();
      } catch (ServiceException e) {
        return e;
      }
      throw new AssertionError("ServiceException이 발생해야 합니다");
    }

    @Test
    @DisplayName("R3 실패: 이미 시작된 회차의 결제 완료 예약은 취소할 수 없다")
    void cancel_confirmedAfterStart_rejected() {
      Schedule schedule = mock(Schedule.class);
      given(schedule.isAlreadyStarted(any(java.time.LocalDateTime.class))).willReturn(true);
      Reservation reservation = reservationWith(ReservationStatus.CONFIRMED, schedule);
      given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));

      ServiceException e = failure(() -> reservationService.cancel(1L, 100L));

      assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.CANCEL_NOT_ALLOWED_AFTER_START);
      verify(reservationCancelManager, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("R3 성공: 시작 전 회차의 결제 완료 예약은 취소할 수 있다")
    void cancel_confirmedBeforeStart_allowed() {
      Schedule schedule = mock(Schedule.class);
      given(schedule.isAlreadyStarted(any(java.time.LocalDateTime.class))).willReturn(false);
      Reservation reservation = reservationWith(ReservationStatus.CONFIRMED, schedule);
      given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));

      reservationService.cancel(1L, 100L);

      verify(reservationCancelManager).cancel(100L);
    }

    @Test
    @DisplayName("R3 성공: 결제 대기(PENDING) 예약은 회차가 시작된 뒤에도 취소할 수 있다 (좌석 반환)")
    void cancel_pendingAfterStart_allowed() {
      Reservation reservation = reservationWith(ReservationStatus.PENDING, null);
      given(reservationRepository.findById(100L)).willReturn(Optional.of(reservation));

      reservationService.cancel(1L, 100L);

      verify(reservation, never()).getSchedule();
      verify(reservationCancelManager).cancel(100L);
    }
  }
}
