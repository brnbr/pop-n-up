package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberNotFoundException;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.qrcode.service.QrService;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.AdminReservationResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

  @InjectMocks private ReservationService reservationService;

  @Mock private ReservationRepository reservationRepository;
  @Mock private ScheduleRepository scheduleRepository;
  @Mock private MemberRepository memberRepository;
  @Mock private QrService qrService;

  @Nested
  @DisplayName("예약 생성 [book]")
  class BookTest {

    @Test
    @DisplayName("정상 요청 시 잔여석이 차감되고 PENDING 예약이 생성된다")
    void book_success() {
      // given
      Long memberId = 1L;
      Long scheduleId = 10L;
      int personCount = 2;

      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
      ReflectionTestUtils.setField(request, "personCount", personCount);

      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);
      Reservation reservation = mock(Reservation.class);

      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(schedule));
      given(schedule.getId()).willReturn(scheduleId);
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(false);

      given(reservationRepository.save(any(Reservation.class))).willReturn(reservation);
      given(reservation.getId()).willReturn(100L);
      given(reservation.getReservationNumber()).willReturn("R20260907TEST");

      // when
      ReservationCreateResponse response = reservationService.book(memberId, request);

      // then
      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo("R20260907TEST");
      verify(schedule).addReservation(personCount);
      verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("이미 활성 예약이 존재하는 스케줄을 다시 예약하면 DUPLICATE_USER_RESERVATION 예외가 발생한다")
    void book_duplicateReservation() {
      // given
      Long memberId = 1L;
      Long scheduleId = 10L;

      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
      ReflectionTestUtils.setField(request, "personCount", 2);

      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);

      given(memberRepository.findById(any())).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(any()))
          .willReturn(Optional.of(schedule));
      given(schedule.getId()).willReturn(scheduleId);
      given(reservationRepository.hasActiveReservation(any(), any())).willReturn(true);

      // when & then
      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION));

      verify(schedule, never()).addReservation(anyInt());
      verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("회원 정보가 없으면 MemberNotFoundException이 발생한다")
    void book_memberNotFound() {
      // given
      Long memberId = 999L;
      ReservationCreateRequest request = new ReservationCreateRequest();
      given(memberRepository.findById(memberId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(MemberNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("예약 확정 [confirmReservation]")
  class ConfirmReservationTest {

    @Test
    @DisplayName("존재하는 예약에 대해 결제 확정을 호출하면 상태가 CONFIRMED로 전이된다")
    void confirmReservation_success() {
      // given
      Long reservationId = 100L;
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));

      // when
      reservationService.confirmReservation(reservationId);

      // then
      verify(reservation).confirm();
    }
  }

  @Nested
  @DisplayName("동적 QR 코드 조회 [getReservationQrCode]")
  class GetReservationQrCodeTest {

    @Test
    @DisplayName("본인의 확정된 예약인 경우 QR 이미지 바이트 배열을 생성하여 반환한다")
    void getReservationQrCode_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;
      String reservationNumber = "R20260907TEST";
      byte[] expectedBytes = new byte[] {0x12, 0x34};

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getReservationNumber()).willReturn(reservationNumber);
      given(qrService.generateQrCodeImage(reservationNumber)).willReturn(expectedBytes);

      // when
      byte[] actualBytes = reservationService.getReservationQrCode(memberId, reservationId);

      // then
      assertThat(actualBytes).isEqualTo(expectedBytes);
      verify(qrService).generateQrCodeImage(reservationNumber);
    }

    @Test
    @DisplayName("본인의 예약이 아닌 경우 UNAUTHORIZED_RESERVATION_ACCESS 예외가 발생한다")
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
    @DisplayName("확정(CONFIRMED) 상태가 아닌 예약 조회 시 INVALID_RESERVATION_STATUS 예외가 발생한다")
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
  @DisplayName("체크인 [checkIn]")
  class CheckInTest {

    @Test
    @DisplayName("유효한 예약 번호로 체크인 요청 시 체크인이 처리되고 CheckInResponse가 반환된다")
    void checkIn_success() {
      // given
      String reservationNumber = "R20260907TEST";
      CheckInRequest request = new CheckInRequest(reservationNumber);

      Member member = mock(Member.class);
      given(member.getName()).willReturn("김철수");

      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(100L);
      given(reservation.getReservationNumber()).willReturn(reservationNumber);
      given(reservation.getMember()).willReturn(member);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.findByReservationNumber(reservationNumber))
          .willReturn(Optional.of(reservation));

      // when
      CheckInResponse response = reservationService.checkIn(request);

      // then
      verify(reservation).checkIn();
      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo(reservationNumber);
      assertThat(response.getMemberName()).isEqualTo("김철수");
      assertThat(response.getPersonCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("예약 취소 [cancel]")
  class CancelTest {

    @Test
    @DisplayName("예약자가 본인의 예약을 취소하면 정상 처리되고 정원이 복구된다")
    void cancel_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      Schedule schedule = mock(Schedule.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);
      given(reservation.getSchedule()).willReturn(schedule);
      given(reservation.getPersonCount()).willReturn(2);

      // when
      reservationService.cancel(memberId, reservationId);

      // then
      verify(reservation).cancel();
      verify(schedule).cancelReservation(2);
    }

    @Test
    @DisplayName("다른 회원의 예약을 취소하려고 하면 UNAUTHORIZED_RESERVATION_ACCESS 예외가 발생한다")
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
    }
  }

  @Nested
  @DisplayName("조회 로직 [allReservations & oneReservation]")
  class QueryTest {

    @Test
    @DisplayName("회원의 예약 목록이 정상적으로 DTO로 변환되어 반환된다")
    void allReservations_success() {
      // given
      Long memberId = 1L;
      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(10L);
      given(reservation.getReservationNumber()).willReturn("R20260907TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.getAllReservation(memberId)).willReturn(List.of(reservation));

      // when
      List<ReservationResponse> results = reservationService.allReservations(memberId);

      // then
      assertThat(results).hasSize(1);
      assertThat(results.get(0).getReservationId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("단건 예약 조회 시 바로잡힌 인자 순서(reservationId, memberId)로 Repository를 호출한다")
    void oneReservation_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(reservationId);
      given(reservation.getReservationNumber()).willReturn("R20260907TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      // Repository 메서드 시그니처: findByIdAndMemberId(Long reservationId, Long memberId)
      given(reservationRepository.findByIdAndMemberId(reservationId, memberId))
          .willReturn(Optional.of(reservation));

      // when
      ReservationResponse response = reservationService.oneReservation(memberId, reservationId);

      // then
      assertThat(response.getReservationId()).isEqualTo(reservationId);
      verify(reservationRepository).findByIdAndMemberId(reservationId, memberId);
    }
  }

  @Nested
  @DisplayName("관리자 예약 목록 조회 [getAdminReservations]")
  class GetAdminReservationsTest {

    @Test
    @DisplayName("팝업 ID와 조건에 맞는 예약 목록을 조회하여 AdminReservationResponse DTO 리스트로 반환한다")
    void getAdminReservations_success() {
      // given
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 7);
      ReservationStatus status = ReservationStatus.CONFIRMED;

      Popup popup = mock(Popup.class);
      given(popup.getId()).willReturn(popupId);
      given(popup.getTitle()).willReturn("성수 아트 팝업");

      Schedule schedule = mock(Schedule.class);
      given(schedule.getPopup()).willReturn(popup);
      given(schedule.getScheduleDate()).willReturn(scheduleDate);
      given(schedule.getStartTime()).willReturn(LocalTime.of(13, 0));
      given(schedule.getEndTime()).willReturn(LocalTime.of(14, 0));

      Member member = mock(Member.class);
      given(member.getId()).willReturn(10L);
      given(member.getName()).willReturn("김철수");

      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(100L);
      given(reservation.getReservationNumber()).willReturn("R20260907TEST01");
      given(reservation.getPersonCount()).willReturn(2);
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getMember()).willReturn(member);
      given(reservation.getSchedule()).willReturn(schedule);

      given(reservationRepository.findAdminReservations(popupId, scheduleDate, status))
          .willReturn(List.of(reservation));

      // when
      List<AdminReservationResponse> responses =
          reservationService.getAdminReservations(popupId, scheduleDate, status);

      // then
      assertThat(responses).hasSize(1);
      AdminReservationResponse response = responses.get(0);
      assertThat(response.getPopupId()).isEqualTo(popupId);
      assertThat(response.getPopupTitle()).isEqualTo("성수 아트 팝업");
      assertThat(response.getReservationid()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo("R20260907TEST01");
    }
  }
}
