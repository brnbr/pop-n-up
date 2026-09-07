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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
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
class ReservationServiceTest {

  @InjectMocks private ReservationService reservationService;

  @Mock private ReservationRepository reservationRepository;
  @Mock private ScheduleRepository scheduleRepository;
  @Mock private MemberRepository memberRepository;
  @Mock private QrService qrService;

  private void printLog(String testName, Object input, Object expected, Object actual) {
    log.info(
        "\n================ [SERVICE TEST] ================"
            + "\n📌 테스트명    : {}"
            + "\n📥 입력값      : {}"
            + "\n🎯 예상 결과   : {}"
            + "\n🔍 실제 결과   : {}"
            + "\n================================================",
        testName,
        input,
        expected,
        actual);
  }

  @Nested
  @DisplayName("예약 생성 [book]")
  class BookTest {

    @Test
    @DisplayName("정상 요청 시 잔여석이 차감되고 PENDING 예약이 생성된다")
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
      given(reservation.getReservationNumber()).willReturn("R20260908TEST");

      ReservationCreateResponse response = reservationService.book(memberId, request);

      printLog(
          "예약 생성 성공",
          "memberId=" + memberId + ", scheduleId=" + scheduleId + ", personCount=" + personCount,
          "reservationId=100, reservationNumber=R20260908TEST",
          "reservationId="
              + response.getReservationId()
              + ", reservationNumber="
              + response.getReservationNumber());

      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo("R20260908TEST");
      verify(schedule).addReservation(personCount);
      verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("이미 활성 예약이 존재하는 스케줄을 다시 예약하면 DUPLICATE_USER_RESERVATION 예외가 발생한다")
    void book_duplicateReservation() {
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

      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                printLog(
                    "중복 예약 검증",
                    "memberId=" + memberId + ", scheduleId=" + scheduleId,
                    ReservationErrorCode.DUPLICATE_USER_RESERVATION.name(),
                    se.getErrorCode().name());
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION);
              });

      verify(schedule, never()).addReservation(anyInt());
      verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("회원 정보가 없으면 MemberNotFoundException이 발생한다")
    void book_memberNotFound() {
      Long memberId = 999L;
      ReservationCreateRequest request = new ReservationCreateRequest();
      given(memberRepository.findById(memberId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationService.book(memberId, request))
          .isInstanceOf(MemberNotFoundException.class)
          .satisfies(
              e ->
                  printLog(
                      "존재하지 않는 회원 예약 요청",
                      "memberId=" + memberId,
                      "MemberNotFoundException",
                      e.getClass().getSimpleName()));
    }
  }

  @Nested
  @DisplayName("동적 QR 코드 조회 [getReservationQrCode]")
  class GetReservationQrCodeTest {

    @Test
    @DisplayName("본인의 확정된 예약인 경우 QR 이미지 바이트 배열을 반환한다")
    void getReservationQrCode_success() {
      Long memberId = 1L;
      Long reservationId = 100L;
      String reservationNumber = "R20260908TEST";
      byte[] expectedBytes = new byte[] {0x12, 0x34};

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getReservationNumber()).willReturn(reservationNumber);
      given(qrService.generateQrCodeImage(reservationNumber)).willReturn(expectedBytes);

      byte[] actualBytes = reservationService.getReservationQrCode(memberId, reservationId);

      printLog(
          "QR 이미지 조회 성공",
          "memberId=" + memberId + ", reservationId=" + reservationId,
          "byte length=" + expectedBytes.length,
          "byte length=" + actualBytes.length);

      assertThat(actualBytes).isEqualTo(expectedBytes);
      verify(qrService).generateQrCodeImage(reservationNumber);
    }

    @Test
    @DisplayName("본인의 예약이 아닌 경우 UNAUTHORIZED_RESERVATION_ACCESS 예외가 발생한다")
    void getReservationQrCode_unauthorized() {
      Long loginMemberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(loginMemberId)).willReturn(false);

      assertThatThrownBy(
              () -> reservationService.getReservationQrCode(loginMemberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                printLog(
                    "타인의 QR 조회 시도",
                    "loginMemberId=" + loginMemberId + ", reservationId=" + reservationId,
                    ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.name(),
                    se.getErrorCode().name());
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
              });
    }

    @Test
    @DisplayName("CONFIRMED 상태가 아니면 INVALID_RESERVATION_STATUS 예외가 발생한다")
    void getReservationQrCode_invalidStatus() {
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);
      given(reservation.getStatus()).willReturn(ReservationStatus.PENDING);

      assertThatThrownBy(() -> reservationService.getReservationQrCode(memberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                printLog(
                    "미확정 예약 QR 조회 시도",
                    "status=" + ReservationStatus.PENDING,
                    ReservationErrorCode.INVALID_RESERVATION_STATUS.name(),
                    se.getErrorCode().name());
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
              });
    }
  }

  @Nested
  @DisplayName("체크인 [checkIn]")
  class CheckInTest {

    @Test
    @DisplayName("예약 번호로 체크인을 요청하면 체크인이 완료되고 응답을 반환한다")
    void checkIn_success() {
      String reservationNumber = "R20260908TEST";
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

      CheckInResponse response = reservationService.checkIn(request);

      printLog(
          "체크인 성공",
          "reservationNumber=" + reservationNumber,
          "id=100, member=김철수, count=2",
          "id="
              + response.getReservationId()
              + ", member="
              + response.getMemberName()
              + ", count="
              + response.getPersonCount());

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
    @DisplayName("취소 요청 시 엔티티 취소 후 Schedule을 비관적 락으로 조회하여 잔여석을 복구한다")
    void cancel_success() {
      Long memberId = 1L;
      Long reservationId = 100L;
      Long scheduleId = 10L;
      int personCount = 2;

      Reservation reservation = mock(Reservation.class);
      Schedule scheduleRef = mock(Schedule.class);
      Schedule lockedSchedule = mock(Schedule.class);

      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(memberId)).willReturn(true);
      given(reservation.getSchedule()).willReturn(scheduleRef);
      given(scheduleRef.getId()).willReturn(scheduleId);
      given(reservation.getPersonCount()).willReturn(personCount);

      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(lockedSchedule));

      reservationService.cancel(memberId, reservationId);

      printLog(
          "예약 취소 및 정원 복구",
          "memberId=" + memberId + ", reservationId=" + reservationId,
          "cancelReservation(" + personCount + ") 호출",
          "정상 취소 및 비관적 락 정원 복구 완료");

      verify(reservation).cancel();
      verify(scheduleRepository).findByIdWithPessimisticLock(scheduleId);
      verify(lockedSchedule).cancelReservation(personCount);
    }

    @Test
    @DisplayName("소유권이 없는 사용자가 취소 시 UNAUTHORIZED_RESERVATION_ACCESS 예외가 발생한다")
    void cancel_unauthorized() {
      Long loginMemberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));
      given(reservation.isOwnedBy(loginMemberId)).willReturn(false);

      assertThatThrownBy(() -> reservationService.cancel(loginMemberId, reservationId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                printLog(
                    "타인의 예약 취소 시도",
                    "loginMemberId=" + loginMemberId + ", reservationId=" + reservationId,
                    ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.name(),
                    se.getErrorCode().name());
                assertThat(se.getErrorCode())
                    .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
              });

      verify(reservation, never()).cancel();
      verify(scheduleRepository, never()).findByIdWithPessimisticLock(any());
    }
  }

  @Nested
  @DisplayName("결제 타임아웃 처리 [payTimeOut]")
  class PayTimeOutTest {

    @Test
    @DisplayName("만료된 PENDING 예약들을 모두 취소하고 비관적 락을 통해 잔여석을 복구한다")
    void payTimeOut_success() {
      Long scheduleId1 = 10L;
      Schedule scheduleRef1 = mock(Schedule.class);
      given(scheduleRef1.getId()).willReturn(scheduleId1);
      Schedule lockedSchedule1 = mock(Schedule.class);

      Reservation reservation1 = mock(Reservation.class);
      given(reservation1.getSchedule()).willReturn(scheduleRef1);
      given(reservation1.getPersonCount()).willReturn(2);

      given(
              reservationRepository.findByStatusAndCreatedAtBefore(
                  eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
          .willReturn(List.of(reservation1));

      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId1))
          .willReturn(Optional.of(lockedSchedule1));

      reservationService.payTimeOut();

      printLog(
          "결제 타임아웃 배치 처리", "만료된 예약 건수=1", "cancelReservation(2) 호출 및 취소 완료", "배치 취소 루프 정상 실행 완료");

      verify(reservation1).cancel();
      verify(lockedSchedule1).cancelReservation(2);
    }
  }

  @Nested
  @DisplayName("조회 로직 [allReservations & oneReservation]")
  class QueryTest {

    @Test
    @DisplayName("회원의 예약 목록이 정상적으로 반환된다")
    void allReservations_success() {
      Long memberId = 1L;
      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(10L);
      given(reservation.getReservationNumber()).willReturn("R20260908TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.getAllReservation(memberId)).willReturn(List.of(reservation));

      List<ReservationResponse> results = reservationService.allReservations(memberId);

      printLog(
          "회원 전체 예약 목록 조회",
          "memberId=" + memberId,
          "목록 크기=1, id=10",
          "목록 크기=" + results.size() + ", id=" + results.get(0).getReservationId());

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getReservationId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("단건 조회 시 인자 순서(reservationId, memberId)로 Repository를 조회한다")
    void oneReservation_success() {
      Long memberId = 1L;
      Long reservationId = 100L;

      Reservation reservation = mock(Reservation.class);
      given(reservation.getId()).willReturn(reservationId);
      given(reservation.getReservationNumber()).willReturn("R20260908TEST");
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getPersonCount()).willReturn(2);

      given(reservationRepository.findByIdAndMemberId(reservationId, memberId))
          .willReturn(Optional.of(reservation));

      ReservationResponse response = reservationService.oneReservation(memberId, reservationId);

      printLog(
          "단건 예약 조회",
          "memberId=" + memberId + ", reservationId=" + reservationId,
          "reservationId=100",
          "reservationId=" + response.getReservationId());

      assertThat(response.getReservationId()).isEqualTo(reservationId);
      verify(reservationRepository).findByIdAndMemberId(reservationId, memberId);
    }
  }

  @Nested
  @DisplayName("관리자 예약 목록 조회 [getAdminReservations]")
  class GetAdminReservationsTest {

    @Test
    @DisplayName("조건에 맞는 예약 목록을 조회하여 반환한다")
    void getAdminReservations_success() {
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 8);
      ReservationStatus status = ReservationStatus.CONFIRMED;

      Popup popup = mock(Popup.class);
      given(popup.getId()).willReturn(popupId);
      given(popup.getTitle()).willReturn("팝업스토어");

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
      given(reservation.getReservationNumber()).willReturn("R20260908TEST01");
      given(reservation.getPersonCount()).willReturn(2);
      given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
      given(reservation.getMember()).willReturn(member);
      given(reservation.getSchedule()).willReturn(schedule);

      given(reservationRepository.findAdminReservations(popupId, scheduleDate, status))
          .willReturn(List.of(reservation));

      List<AdminReservationResponse> responses =
          reservationService.getAdminReservations(popupId, scheduleDate, status);

      printLog(
          "관리자 예약 목록 조회",
          "popupId=" + popupId + ", date=" + scheduleDate + ", status=" + status,
          "리스트 크기=1, popupId=1",
          "리스트 크기=" + responses.size() + ", popupId=" + responses.get(0).getPopupId());

      assertThat(responses).hasSize(1);
      assertThat(responses.get(0).getPopupId()).isEqualTo(popupId);
      assertThat(responses.get(0).getReservationNumber()).isEqualTo("R20260908TEST01");
    }
  }
}
