package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
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
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
  @Mock private ReservationCancelManager reservationCancelManager;
  @Mock private ScheduleCapacityCache scheduleCapacityCache;

  @Nested
  @DisplayName("조건부 업데이트 기반 예약 생성 [bookWithConditionalUpdate]")
  class BookWithConditionalUpdateTest {

    @Test
    @DisplayName("성공: 모든 검증 및 정원 증가 성공 시 예약이 생성되고 응답을 반환한다")
    void bookWithConditionalUpdate_success() {
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
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(false);
      given(scheduleCapacityCache.tryReserve(scheduleId, personCount)).willReturn(true);
      given(scheduleRepository.findByIdForValidation(scheduleId)).willReturn(Optional.of(schedule));
      given(scheduleRepository.tryIncreaseCapacity(scheduleId, personCount)).willReturn(1);
      given(reservationRepository.save(any(Reservation.class))).willReturn(reservation);
      given(reservation.getId()).willReturn(100L);
      given(reservation.getReservationNumber()).willReturn("R20260915TEST");

      // when
      ReservationCreateResponse response =
          reservationService.bookWithConditionalUpdate(memberId, request);

      // then
      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo("R20260915TEST");

      verify(schedule).validateBookable(any(LocalDateTime.class));
      verify(reservationRepository).save(any(Reservation.class));
      verify(scheduleCapacityCache, never()).compensate(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: 회원이 존재하지 않으면 MEMBER_NOT_FOUND 예외가 발생한다")
    void bookWithConditionalUpdate_memberNotFound() {
      // given
      Long memberId = 1L;
      ReservationCreateRequest request = new ReservationCreateRequest();
      given(memberRepository.findById(memberId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> reservationService.bookWithConditionalUpdate(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));

      verify(scheduleCapacityCache, never()).tryReserve(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: 이미 활성 예약이 존재하면 DUPLICATE_USER_RESERVATION 예외가 발생한다")
    void bookWithConditionalUpdate_duplicateReservation() {
      // given
      Long memberId = 1L;
      Long scheduleId = 10L;

      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
      ReflectionTestUtils.setField(request, "personCount", 2);

      Member member = mock(Member.class);
      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(true);

      // when & then
      assertThatThrownBy(() -> reservationService.bookWithConditionalUpdate(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION));

      verify(scheduleCapacityCache, never()).tryReserve(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: Redis 캐시 차단 시 SCHEDULE_CAPACITY_EXCEEDED 예외가 발생하며 보상 트랜잭션은 호출되지 않는다")
    void bookWithConditionalUpdate_redisCacheExceeded() {
      // given
      Long memberId = 1L;
      Long scheduleId = 10L;
      int personCount = 2;

      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
      ReflectionTestUtils.setField(request, "personCount", personCount);

      Member member = mock(Member.class);
      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(false);
      given(scheduleCapacityCache.tryReserve(scheduleId, personCount)).willReturn(false);

      // when & then
      assertThatThrownBy(() -> reservationService.bookWithConditionalUpdate(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED));

      verify(scheduleCapacityCache, never()).compensate(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: DB 정원 증가 실패 시 SCHEDULE_CAPACITY_EXCEEDED 예외가 발생하고 Redis 보상 메서드가 호출된다")
    void bookWithConditionalUpdate_dbCapacityExceeded_triggersCompensate() {
      // given
      Long memberId = 1L;
      Long scheduleId = 10L;
      int personCount = 2;

      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", scheduleId);
      ReflectionTestUtils.setField(request, "personCount", personCount);

      Member member = mock(Member.class);
      Schedule schedule = mock(Schedule.class);

      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(false);
      given(scheduleCapacityCache.tryReserve(scheduleId, personCount)).willReturn(true);
      given(scheduleRepository.findByIdForValidation(scheduleId)).willReturn(Optional.of(schedule));
      given(scheduleRepository.tryIncreaseCapacity(scheduleId, personCount)).willReturn(0);

      // when & then
      assertThatThrownBy(() -> reservationService.bookWithConditionalUpdate(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED));

      verify(scheduleCapacityCache).compensate(scheduleId, personCount);
      verify(reservationRepository, never()).save(any(Reservation.class));
    }
  }

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
  @DisplayName("체크인 [checkIn]")
  class CheckInTest {

    @Test
    @DisplayName("성공: 비관적 락으로 조회된 예약에 checkIn을 호출하고 응답 DTO를 반환한다")
    void checkIn_success() {
      // given
      String reservationNumber = "R20260915TEST";
      CheckInRequest request = new CheckInRequest(reservationNumber);

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
      CheckInResponse response = reservationService.checkIn(request);

      // then
      verify(reservation).checkIn();
      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo(reservationNumber);
      assertThat(response.getMemberName()).isEqualTo("홍길동");
      assertThat(response.getPersonCount()).isEqualTo(2);
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
    void allReservations_success() {
      // given
      Long memberId = 1L;
      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(10L);
      given(reservation.getReservationNumber()).willReturn("R20260915TEST");
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
    @DisplayName("성공: 예약 ID와 회원 ID로 단건 예약을 정상 조회한다")
    void oneReservation_success() {
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
      ReservationResponse response = reservationService.oneReservation(memberId, reservationId);

      // then
      assertThat(response.getReservationId()).isEqualTo(reservationId);
      verify(reservationRepository).findByIdAndMemberId(reservationId, memberId);
    }

    @Test
    @DisplayName("실패: 단건 조회 시 내역이 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void oneReservation_notFound() {
      // given
      Long memberId = 1L;
      Long reservationId = 100L;

      given(reservationRepository.findByIdAndMemberId(reservationId, memberId))
          .willReturn(Optional.empty());

      // when & then
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

      given(reservationRepository.findAdminReservations(popupId, scheduleDate, status))
          .willReturn(List.of(reservation));

      // when
      List<AdminReservationResponse> responses =
          reservationService.getAdminReservations(popupId, scheduleDate, status);

      // then
      assertThat(responses).hasSize(1);
      assertThat(responses.get(0).getReservationId()).isEqualTo(100L);
      assertThat(responses.get(0).getReservationNumber()).isEqualTo("R20260915ADMIN");
    }
  }
}
