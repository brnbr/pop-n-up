package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberNotFoundException;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.qrcode.service.QrService;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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

  private Member testMember;
  private Schedule testSchedule;
  private Reservation testReservation;

  @BeforeEach
  void setUp() {
    testMember = Member.createLocal("user@test.com", "pass1234", "Tester");
    ReflectionTestUtils.setField(testMember, "id", 1L);

    testSchedule =
        Schedule.createSchedule(
            null, LocalDate.now(), LocalTime.of(13, 0), LocalTime.of(14, 0), 10);
    ReflectionTestUtils.setField(testSchedule, "id", 100L);

    testReservation = Reservation.createReservation("R20260909TEST01", testMember, testSchedule, 2);
    ReflectionTestUtils.setField(testReservation, "id", 500L);
  }

  @Nested
  @DisplayName("예약 생성(book)")
  class Book {

    @Test
    @DisplayName("성공: 스케줄 인원이 증가하고 PENDING 예약이 생성된다")
    void success() {
      ReservationCreateRequest req = new ReservationCreateRequest();
      ReflectionTestUtils.setField(req, "scheduleId", 100L);
      ReflectionTestUtils.setField(req, "personCount", 3);

      given(memberRepository.findById(1L)).willReturn(Optional.of(testMember));
      given(scheduleRepository.findByIdWithPessimisticLock(100L))
          .willReturn(Optional.of(testSchedule));
      given(reservationRepository.hasActiveReservation(100L, 1L)).willReturn(false);
      given(reservationRepository.save(any(Reservation.class))).willReturn(testReservation);

      ReservationCreateResponse res = reservationService.book(1L, req);

      assertThat(res.getReservationId()).isEqualTo(500L);
      assertThat(testSchedule.getNowCapacity()).isEqualTo(3);
      verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("실패: 회원이 존재하지 않으면 MemberNotFoundException 던짐")
    void fail_MemberNotFound() {
      ReservationCreateRequest req = new ReservationCreateRequest();
      given(memberRepository.findById(1L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.book(1L, req))
          .isInstanceOf(MemberNotFoundException.class);
    }

    @Test
    @DisplayName("실패: 스케줄이 존재하지 않으면 SCHEDULE_NOT_FOUND 예외 발생")
    void fail_ScheduleNotFound() {
      ReservationCreateRequest req = new ReservationCreateRequest();
      ReflectionTestUtils.setField(req, "scheduleId", 999L);

      given(memberRepository.findById(1L)).willReturn(Optional.of(testMember));
      given(scheduleRepository.findByIdWithPessimisticLock(999L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.book(1L, req))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
              });
    }

    @Test
    @DisplayName("실패: 동일 회차 중복 예약 시 DUPLICATE_USER_RESERVATION 예외 발생")
    void fail_DuplicateReservation() {
      ReservationCreateRequest req = new ReservationCreateRequest();
      ReflectionTestUtils.setField(req, "scheduleId", 100L);
      ReflectionTestUtils.setField(req, "personCount", 2);

      given(memberRepository.findById(1L)).willReturn(Optional.of(testMember));
      given(scheduleRepository.findByIdWithPessimisticLock(100L))
          .willReturn(Optional.of(testSchedule));
      given(reservationRepository.hasActiveReservation(100L, 1L)).willReturn(true);

      assertThatThrownBy(() -> reservationService.book(1L, req))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION);
              });
      verify(reservationRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("예약 확정(confirmReservation)")
  class Confirm {

    @Test
    @DisplayName("성공: PENDING 상태의 예약을 CONFIRMED로 변경")
    void success() {
      given(reservationRepository.findById(500L)).willReturn(Optional.of(testReservation));

      reservationService.confirmReservation(500L);

      assertThat(testReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("실패: 예약이 없으면 RESERVATION_NOT_FOUND 예외 발생")
    void fail_NotFound() {
      given(reservationRepository.findById(999L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.confirmReservation(999L))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
              });
    }
  }

  @Nested
  @DisplayName("QR 조회(getReservationQrCode)")
  class QrCode {

    @Test
    @DisplayName("성공: 확정(CONFIRMED) 상태이고 본인 예약이면 이미지 바이트 반환")
    void success() {
      testReservation.confirm();
      byte[] dummyQr = new byte[] {1, 2, 3};

      given(reservationRepository.findById(500L)).willReturn(Optional.of(testReservation));
      given(qrService.generateQrCodeImage(testReservation.getReservationNumber()))
          .willReturn(dummyQr);

      byte[] result = reservationService.getReservationQrCode(1L, 500L);

      assertThat(result).isEqualTo(dummyQr);
    }

    @Test
    @DisplayName("실패: 타인의 예약을 조회하면 UNAUTHORIZED_RESERVATION_ACCESS 예외 발생")
    void fail_Unauthorized() {
      testReservation.confirm();
      given(reservationRepository.findById(500L)).willReturn(Optional.of(testReservation));

      assertThatThrownBy(() -> reservationService.getReservationQrCode(999L, 500L))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
              });
    }

    @Test
    @DisplayName("실패: 확정 상태가 아니면(PENDING) INVALID_RESERVATION_STATUS 예외 발생")
    void fail_NotConfirmed() {
      given(reservationRepository.findById(500L)).willReturn(Optional.of(testReservation));

      assertThatThrownBy(() -> reservationService.getReservationQrCode(1L, 500L))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
              });
    }
  }

  @Nested
  @DisplayName("체크인(checkIn)")
  class CheckIn {

    @Test
    @DisplayName("성공: CONFIRMED 예약을 USED로 전환하고 응답 반환")
    void success() {
      testReservation.confirm();
      CheckInRequest req = new CheckInRequest();
      ReflectionTestUtils.setField(
          req, "reservationNumber", testReservation.getReservationNumber());

      given(reservationRepository.findByReservationNumber(testReservation.getReservationNumber()))
          .willReturn(Optional.of(testReservation));

      CheckInResponse res = reservationService.checkIn(req);

      assertThat(testReservation.getStatus()).isEqualTo(ReservationStatus.USED);
      assertThat(res.getReservationNumber()).isEqualTo(testReservation.getReservationNumber());
    }

    @Test
    @DisplayName("실패: 예약 번호 불일치 시 RESERVATION_NOT_FOUND 예외 발생")
    void fail_NotFound() {
      CheckInRequest req = new CheckInRequest();
      ReflectionTestUtils.setField(req, "reservationNumber", "UNKNOWN");
      given(reservationRepository.findByReservationNumber("UNKNOWN")).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.checkIn(req))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
              });
    }
  }

  @Nested
  @DisplayName("사용자 예약 취소(cancel)")
  class Cancel {

    @Test
    @DisplayName("성공: 소유자 검증 통과 후 ReservationCancelManager로 위임")
    void success() {
      given(reservationRepository.findById(500L)).willReturn(Optional.of(testReservation));

      reservationService.cancel(1L, 500L);

      verify(reservationCancelManager).cancel(testReservation);
    }

    @Test
    @DisplayName("실패: 소유자 불일치 시 취소 매니저 호출 없이 UNAUTHORIZED_RESERVATION_ACCESS 예외 발생")
    void fail_Unauthorized() {
      given(reservationRepository.findById(500L)).willReturn(Optional.of(testReservation));

      assertThatThrownBy(() -> reservationService.cancel(999L, 500L))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
              });
      verify(reservationCancelManager, never()).cancel(any());
    }
  }

  @Nested
  @DisplayName("조회 계열 메서드")
  class Queries {

    @Test
    @DisplayName("단건 조회: 본인 예약이 맞으면 ReservationResponse 반환")
    void oneReservation_Success() {
      given(reservationRepository.findByIdAndMemberId(500L, 1L))
          .willReturn(Optional.of(testReservation));

      ReservationResponse res = reservationService.oneReservation(1L, 500L);

      assertThat(res.getReservationId()).isEqualTo(500L);
      assertThat(res.getReservationNumber()).isEqualTo(testReservation.getReservationNumber());
    }

    @Test
    @DisplayName("단건 조회: 없는 예약이거나 타인 예약이면 RESERVATION_NOT_FOUND 예외 발생")
    void oneReservation_NotFound() {
      given(reservationRepository.findByIdAndMemberId(500L, 1L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.oneReservation(1L, 500L))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
              });
    }
  }

  @Nested
  @DisplayName("미방문 만료(expirePastReservation)")
  class ExpirePastReservation {

    @Test
    @DisplayName("조회된 확정 예약 목록 전체를 EXPIRED 상태로 전이")
    void success() {
      testReservation.confirm();
      given(
              reservationRepository.findExpiredReservations(
                  any(LocalDate.class), any(LocalTime.class)))
          .willReturn(List.of(testReservation));

      reservationService.expirePastReservation();

      assertThat(testReservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("만료 대상이 비어있으면 로직을 즉시 종료")
    void success_EmptyList() {
      given(
              reservationRepository.findExpiredReservations(
                  any(LocalDate.class), any(LocalTime.class)))
          .willReturn(List.of());

      reservationService.expirePastReservation();

      verify(reservationRepository, never()).saveAll(any());
    }
  }
}
