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
      given(reservation.getReservationNumber()).willReturn("R20260903TEST");

      // when
      ReservationCreateResponse response = reservationService.book(memberId, request);

      // then
      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo("R20260903TEST");
      verify(schedule).addReservation(personCount); // 잔여석 차감 호출 검증
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

      // 1. 회원 및 스케줄 조회 성공 세팅
      given(memberRepository.findById(any())).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(any()))
          .willReturn(Optional.of(schedule));
      given(schedule.getId()).willReturn(scheduleId);

      // 2. 이미 활성화된 중복 예약이 존재함(true)을 세팅
      given(reservationRepository.hasActiveReservation(any(), any())).willReturn(true);

      // when & then
      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION));

      // then
      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION));

      // 1. 중복이므로 정원 차감 메서드가 절대 호출되지 않았어야 함
      verify(schedule, never()).addReservation(anyInt());

      // 2. 중복이므로 DB에 예약 저장도 절대 호출되지 않았어야 함
      verify(reservationRepository, never()).save(any(Reservation.class));
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
  @DisplayName("예약 취소 [cancel]")
  class CancelTest {

    @Test
    @DisplayName("예약자가 본인의 예약을 취소하면 정상 처리되고 정원이 복구된다")
    void cancel_success() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(member);
      given(member.getId()).willReturn(memberId);
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
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
      Long actualOwnerId = 2L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      Member member = mock(Member.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(member);
      given(member.getId()).willReturn(actualOwnerId);

      // when & then
      assertThatThrownBy(() -> reservationService.cancel(loginMemberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS));
    }

    @Test
    @DisplayName("이미 취소된 예약을 다시 취소하려고 하면 ALREADY_CANCELED_RESERVATION 예외가 발생한다")
    void cancel_alreadyCanceled() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      Member member = mock(Member.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(member);
      given(member.getId()).willReturn(memberId);
      given(reservation.getStatus()).willReturn(ReservationStatus.CANCELED);

      // when & then
      assertThatThrownBy(() -> reservationService.cancel(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.ALREADY_CANCELED_RESERVATION));
    }

    @Test
    @DisplayName("이미 사용된 예약을 취소하려고 하면 ALREADY_PROCESSED_RESERVATION 예외가 발생한다")
    void cancel_alreadyUsed() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      Member member = mock(Member.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(member);
      given(member.getId()).willReturn(memberId);
      given(reservation.getStatus()).willReturn(ReservationStatus.USED);

      // when & then
      assertThatThrownBy(() -> reservationService.cancel(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION));
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
      given(reservation.getReservationNumber()).willReturn("R20260903TEST");
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
    @DisplayName("존재하지 않는 단건 예약 조회 시 RESERVATION_NOT_FOUND 예외가 발생한다")
    void oneReservation_notFound() {
      // given
      Long memberId = 1L;
      Long reservationId = 999L;

      given(reservationRepository.findByIdAndMemberId(memberId, reservationId))
          .willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> reservationService.oneReservation(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    @Nested
    @DisplayName("관리자 예약 목록 조회 [getAdminReservations]")
    class GetAdminReservationsTest {

      @Test
      @DisplayName("팝업 ID와 조건에 맞는 예약 목록을 조회하여 AdminReservationResponse DTO 리스트로 반환한다")
      void getAdminReservations_success() {
        // given
        Long popupId = 1L;
        LocalDate scheduleDate = LocalDate.of(2026, 9, 3);
        ReservationStatus status = ReservationStatus.CONFIRMED;

        // Mock 엔티티 세팅
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
        given(reservation.getReservationNumber()).willReturn("R20260903TEST01");
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
        assertThat(response.getReservationNumber()).isEqualTo("R20260903TEST01");
        assertThat(response.getPersoncount()).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(response.getMemberId()).isEqualTo(10L);
        assertThat(response.getMemberName()).isEqualTo("김철수");
        assertThat(response.getScheduledDate()).isEqualTo(scheduleDate);
        assertThat(response.getStartTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(response.getEndTime()).isEqualTo(LocalTime.of(14, 0));
      }
    }
  }
}
