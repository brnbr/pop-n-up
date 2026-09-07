package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    @DisplayName("성공: 유효한 요청 시 비관적 락으로 잔여석 차감 후 PENDING 상태의 예약이 생성된다")
    void book_success() {
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
      given(reservation.getReservationNumber()).willReturn("R20260904TEST");

      ReservationCreateResponse response = reservationService.book(memberId, request);

      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo("R20260904TEST");
      verify(schedule).addReservation(personCount);
      verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("실패: 회원이 없으면 MemberNotFoundException이 발생한다")
    void book_memberNotFound() {
      Long memberId = 999L;
      ReservationCreateRequest request = new ReservationCreateRequest();
      given(memberRepository.findById(memberId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(MemberNotFoundException.class);

      verify(scheduleRepository, never()).findByIdWithPessimisticLock(any());
      verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("실패: 회차가 없으면 SCHEDULE_NOT_FOUND 예외가 발생한다")
    void book_scheduleNotFound() {
      Long memberId = 1L;
      Long scheduleId = 999L;
      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);

      Member member = mock(Member.class);
      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND));

      verify(reservationRepository, never()).hasActiveReservation(any(), any());
      verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("실패: 이미 활성화된 중복 예약이 있으면 DUPLICATE_USER_RESERVATION 예외가 발생한다")
    void book_duplicateReservation() {
      Long memberId = 1L;
      Long scheduleId = 10L;

      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
      ReflectionTestUtils.setField(request, "personCount", 2);

      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);

      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(schedule));
      given(schedule.getId()).willReturn(scheduleId);
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(true);

      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION));

      verify(schedule, never()).addReservation(anyInt());
      verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("실패: 잔여 좌석이 부족하여 addReservation()에서 예외 발생 시 저장이 중단되고 예외가 전파된다")
    void book_capacityExceeded() {
      Long memberId = 1L;
      Long scheduleId = 10L;
      int personCount = 5;

      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
      ReflectionTestUtils.setField(request, "personCount", personCount);

      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);

      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(schedule));
      given(schedule.getId()).willReturn(scheduleId);
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(false);

      // 잔여석 초과 도메인 예외 모킹 (Schedule 도메인 내부 예외 전파)
      doThrow(new IllegalStateException("잔여 좌석이 부족합니다."))
          .when(schedule)
          .addReservation(personCount);

      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("잔여 좌석이 부족합니다.");

      // 예약이 DB에 영속화되지 않았는지 검증
      verify(reservationRepository, never()).save(any(Reservation.class));
    }
  }

  @Nested
  @DisplayName("예약 확정 [confirmReservation]")
  class ConfirmReservationTest {

    @Test
    @DisplayName("성공: 존재하는 예약에 대해 confirm()이 정상 호출된다")
    void confirmReservation_success() {
      Long reservationId = 100L;
      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));

      reservationService.confirmReservation(reservationId);

      verify(reservation).confirm();
    }

    @Test
    @DisplayName("실패: 예약이 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void confirmReservation_notFound() {
      Long reservationId = 999L;
      given(reservationRepository.findById(reservationId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.confirmReservation(reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }
  }

  @Nested
  @DisplayName("예약 취소 [cancel]")
  class CancelTest {

    @Test
    @DisplayName("성공: 본인 예약 취소 시 cancel() 호출 후 락을 걸어 스케줄 정원을 복구한다")
    void cancel_success() {
      Long memberId = 1L;
      Long reservationId = 100L;
      Long scheduleId = 10L;

      Reservation reservation = mock(Reservation.class);
      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(member);
      given(member.getId()).willReturn(memberId);

      given(reservation.getSchedule()).willReturn(schedule);
      given(schedule.getId()).willReturn(scheduleId);
      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(schedule));
      given(reservation.getPersonCount()).willReturn(2);

      reservationService.cancel(memberId, reservationId);

      verify(reservation).cancel();
      verify(scheduleRepository).findByIdWithPessimisticLock(scheduleId);
      verify(schedule).cancelReservation(2);
    }

    @Test
    @DisplayName("실패: 예약이 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void cancel_reservationNotFound() {
      Long memberId = 1L;
      Long reservationId = 999L;
      given(reservationRepository.findById(reservationId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.cancel(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

      verify(scheduleRepository, never()).findByIdWithPessimisticLock(any());
    }

    @Test
    @DisplayName("실패: 다른 사람의 예약이면 UNAUTHORIZED_RESERVATION_ACCESS 예외가 발생한다")
    void cancel_unauthorized() {
      Long loginMemberId = 1L;
      Long ownerId = 2L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      Member owner = mock(Member.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(owner);
      given(owner.getId()).willReturn(ownerId);

      assertThatThrownBy(() -> reservationService.cancel(loginMemberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS));

      verify(reservation, never()).cancel();
      verify(scheduleRepository, never()).findByIdWithPessimisticLock(any());
    }

    @Test
    @DisplayName("실패: 엔티티 취소 불가능 상태일 때 INVALID_RESERVATION_STATUS 예외가 전파된다")
    void cancel_invalidStatus() {
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      Member member = mock(Member.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(member);
      given(member.getId()).willReturn(memberId);
      willThrow(ReservationErrorCode.INVALID_RESERVATION_STATUS.toException())
          .given(reservation)
          .cancel();

      assertThatThrownBy(() -> reservationService.cancel(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS));

      verify(scheduleRepository, never()).findByIdWithPessimisticLock(any());
    }

    @Test
    @DisplayName("실패: 스케줄을 찾을 수 없으면 SCHEDULE_NOT_FOUND 예외가 발생한다")
    void cancel_scheduleNotFound() {
      Long memberId = 1L;
      Long reservationId = 100L;
      Long scheduleId = 10L;

      Reservation reservation = mock(Reservation.class);
      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.getMember()).willReturn(member);
      given(member.getId()).willReturn(memberId);
      given(reservation.getSchedule()).willReturn(schedule);
      given(schedule.getId()).willReturn(scheduleId);

      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.cancel(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND));

      verify(schedule, never()).cancelReservation(anyInt());
    }
  }

  @Nested
  @DisplayName("조회 로직 [allReservations & oneReservation]")
  class QueryTest {

    @Test
    @DisplayName("성공: 회원의 예약 목록이 DTO 리스트로 정상 변환된다")
    void allReservations_success() {
      Long memberId = 1L;
      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(10L);
      given(reservation.getReservationNumber()).willReturn("R20260904TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.getAllReservation(memberId)).willReturn(List.of(reservation));

      List<ReservationResponse> results = reservationService.allReservations(memberId);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getReservationId()).isEqualTo(10L);
      assertThat(results.get(0).getReservationNumber()).isEqualTo("R20260904TEST");
    }

    @Test
    @DisplayName("성공: findByIdAndMemberId에 reservationId와 memberId 순서로 올바르게 전달하여 단건 조회한다")
    void oneReservation_success() {
      Long memberId = 1L;
      Long reservationId = 100L;
      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(reservationId);
      given(reservation.getReservationNumber()).willReturn("R20260904TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.findByIdAndMemberId(reservationId, memberId))
          .willReturn(Optional.of(reservation));

      ReservationResponse response = reservationService.oneReservation(memberId, reservationId);

      assertThat(response.getReservationId()).isEqualTo(reservationId);
      assertThat(response.getReservationNumber()).isEqualTo("R20260904TEST");
      verify(reservationRepository).findByIdAndMemberId(reservationId, memberId);
    }

    @Test
    @DisplayName("실패: 단건 조회 결과가 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void oneReservation_notFound() {
      Long memberId = 1L;
      Long reservationId = 999L;

      given(reservationRepository.findByIdAndMemberId(reservationId, memberId))
          .willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.oneReservation(memberId, reservationId))
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
    @DisplayName("성공: 관리자 조건에 맞춰 명단을 DTO 리스트로 반환한다")
    void getAdminReservations_success() {
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 4);
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
      given(reservation.getReservationNumber()).willReturn("R20260904TEST01");
      given(reservation.getPersonCount()).willReturn(2);
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getMember()).willReturn(member);
      given(reservation.getSchedule()).willReturn(schedule);

      given(reservationRepository.findAdminReservations(popupId, scheduleDate, status))
          .willReturn(List.of(reservation));

      List<AdminReservationResponse> responses =
          reservationService.getAdminReservations(popupId, scheduleDate, status);

      assertThat(responses).hasSize(1);
      AdminReservationResponse response = responses.get(0);
      assertThat(response.getPopupId()).isEqualTo(popupId);
      assertThat(response.getReservationid()).isEqualTo(100L);
      assertThat(response.getMemberName()).isEqualTo("김철수");
    }
  }

  @Nested
  @DisplayName("미결제 타임아웃 처리 [payTimeOut]")
  class PayTimeOutTest {

    @Test
    @DisplayName("성공: 만료된 PENDING 예약들을 취소하고 락을 걸어 회차 정원을 복구한다")
    void payTimeOut_success() {
      Long scheduleId = 10L;

      Reservation res = mock(Reservation.class);
      Schedule sch = mock(Schedule.class);
      given(res.getSchedule()).willReturn(sch);
      given(sch.getId()).willReturn(scheduleId);
      given(res.getPersonCount()).willReturn(2);

      given(
              reservationRepository.findByStatusAndCreatedAtBefore(
                  eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
          .willReturn(List.of(res));

      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(sch));

      reservationService.payTimeOut();

      verify(res).cancel();
      verify(scheduleRepository).findByIdWithPessimisticLock(scheduleId);
      verify(sch).cancelReservation(2);
    }
  }
}
