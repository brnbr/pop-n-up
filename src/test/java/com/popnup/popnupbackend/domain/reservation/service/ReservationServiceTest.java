package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
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
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

  @Mock private ReservationRepository reservationRepository;
  @Mock private ScheduleRepository scheduleRepository;
  @Mock private MemberRepository memberRepository;
  @Mock private QrService qrService;
  @Mock private ReservationCancelManager reservationCancelManager;

  @InjectMocks private ReservationService reservationService;

  private Member member;
  private Schedule schedule;
  private Reservation reservation;

  @BeforeEach
  void setUp() {
    member = createMemberWithId(1L);
    Popup popup = createPopup(PopupStatus.OPEN);
    schedule =
        Schedule.createSchedule(
            popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
    ReflectionTestUtils.setField(schedule, "id", 100L);

    reservation = Reservation.createReservation("R20260912TEST0001", member, schedule, 2);
    ReflectionTestUtils.setField(reservation, "id", 10L);
  }

  private Member createMemberWithId(Long id) {
    Member m = Member.createLocal("test@test.com", "password", "테스터");
    ReflectionTestUtils.setField(m, "id", id);
    return m;
  }

  private Popup createPopup(PopupStatus status) {
    return Popup.builder()
        .title("테스트 팝업")
        .category(PopupCategory.ETC)
        .region("서울")
        .address("서울시 강남구")
        .startDate(LocalDate.now().minusDays(10))
        .endDate(LocalDate.now().plusDays(10))
        .isFree(true)
        .price(0)
        .status(status)
        .build();
  }

  // ReservationCreateRequest는 생성자/빌더가 없는 @Getter 전용 DTO라 Mockito.mock()으로 stub
  private ReservationCreateRequest mockCreateRequest(Long scheduleId, int personCount) {
    ReservationCreateRequest request = Mockito.mock(ReservationCreateRequest.class);
    given(request.getScheduleId()).willReturn(scheduleId);
    given(request.getPersonCount()).willReturn(personCount);
    return request;
  }

  @Nested
  @DisplayName("book 검증")
  class Book {

    @Test
    @DisplayName("정상 예약 생성")
    void success() {
      ReservationCreateRequest request = mockCreateRequest(100L, 2);

      given(memberRepository.findById(1L)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.of(schedule));
      given(reservationRepository.hasActiveReservation(100L, 1L)).willReturn(false);
      given(reservationRepository.save(any(Reservation.class)))
          .willAnswer(invocation -> invocation.getArgument(0));

      log.info("[book.success] input(memberId=1, scheduleId=100, personCount=2)");

      ReservationCreateResponse response = reservationService.book(1L, request);

      log.info(
          "[book.success] expectedNumberPrefix=R actualReservationNumber={} scheduleNowCapacity={}",
          response.getReservationNumber(),
          schedule.getNowCapacity());

      assertThat(response.getReservationNumber()).startsWith("R");
      assertThat(schedule.getNowCapacity()).isEqualTo(2);
      verify(reservationRepository, times(1)).save(any(Reservation.class));
    }

    @Test
    @DisplayName("회원이 존재하지 않으면 예외 발생")
    void memberNotFound() {
      ReservationCreateRequest request = Mockito.mock(ReservationCreateRequest.class);
      given(memberRepository.findById(1L)).willReturn(Optional.empty());

      log.info("[book.memberNotFound] input(memberId=1)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.book(1L, request));

      log.info(
          "[book.memberNotFound] expectedErrorCode={} actualErrorCode={}",
          MemberErrorCode.MEMBER_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("스케줄이 존재하지 않으면 예외 발생")
    void scheduleNotFound() {
      ReservationCreateRequest request = Mockito.mock(ReservationCreateRequest.class);
      given(request.getScheduleId()).willReturn(100L);
      given(memberRepository.findById(1L)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.empty());

      log.info("[book.scheduleNotFound] input(memberId=1, scheduleId=100)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.book(1L, request));

      log.info(
          "[book.scheduleNotFound] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.SCHEDULE_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 활성 예약이 있으면 예외 발생")
    void duplicateReservation() {
      ReservationCreateRequest request = Mockito.mock(ReservationCreateRequest.class);
      given(request.getScheduleId()).willReturn(100L);
      given(memberRepository.findById(1L)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(100L)).willReturn(Optional.of(schedule));
      given(reservationRepository.hasActiveReservation(100L, 1L)).willReturn(true);

      log.info("[book.duplicateReservation] input(memberId=1, scheduleId=100)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.book(1L, request));

      log.info(
          "[book.duplicateReservation] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.DUPLICATE_USER_RESERVATION,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION);
    }

    @Test
    @DisplayName("Popup이 OPEN 상태가 아니면 예외 발생 (addReservation 내부 검증 전파 확인)")
    void popupNotOpen() {
      Popup closedPopup = createPopup(PopupStatus.CLOSED);
      Schedule closedSchedule =
          Schedule.createSchedule(
              closedPopup,
              LocalDate.now().plusDays(1),
              LocalTime.of(10, 0),
              LocalTime.of(11, 0),
              10);
      ReflectionTestUtils.setField(closedSchedule, "id", 101L);

      ReservationCreateRequest request = mockCreateRequest(101L, 2);
      given(memberRepository.findById(1L)).willReturn(Optional.of(member));
      given(scheduleRepository.findByIdWithPessimisticLock(101L))
          .willReturn(Optional.of(closedSchedule));
      given(reservationRepository.hasActiveReservation(101L, 1L)).willReturn(false);

      log.info("[book.popupNotOpen] input(memberId=1, scheduleId=101, popupStatus=CLOSED)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.book(1L, request));

      log.info(
          "[book.popupNotOpen] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_POPUP_NOT_FOUND);
    }
  }

  @Nested
  @DisplayName("confirmReservation 검증")
  class ConfirmReservation {

    @Test
    @DisplayName("정상 확정 처리")
    void success() {
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));

      log.info("[confirmReservation.success] input(reservationId=10, paymentSucceeded=true)");

      reservationService.confirmReservation(10L, true);

      log.info(
          "[confirmReservation.success] expectedStatus=CONFIRMED actualStatus={}",
          reservation.getStatus());

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("결제 실패면 PAYMENT_NOT_COMPLETED 예외 발생")
    void paymentFailed() {
      given(reservationRepository.findByIdWithPessimisticLock(10L))
          .willReturn(Optional.of(reservation));

      log.info(
          "[confirmReservation.paymentFailed] input(reservationId=10, paymentSucceeded=false)");

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> reservationService.confirmReservation(10L, false));

      log.info(
          "[confirmReservation.paymentFailed] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.PAYMENT_NOT_COMPLETED,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.PAYMENT_NOT_COMPLETED);
    }

    @Test
    @DisplayName("예약이 존재하지 않으면 예외 발생")
    void notFound() {
      given(reservationRepository.findByIdWithPessimisticLock(999L)).willReturn(Optional.empty());

      log.info("[confirmReservation.notFound] input(reservationId=999)");

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> reservationService.confirmReservation(999L, true));

      log.info(
          "[confirmReservation.notFound] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.RESERVATION_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }
  }

  @Nested
  @DisplayName("getReservationQrCode 검증")
  class GetReservationQrCode {

    @Test
    @DisplayName("CONFIRMED 상태의 본인 예약이면 QR 이미지를 반환한다")
    void success() {
      reservation.confirm(true);
      given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));
      byte[] fakeImage = new byte[] {1, 2, 3};
      given(qrService.generateQrCodeImage("R20260912TEST0001")).willReturn(fakeImage);

      log.info("[getReservationQrCode.success] input(memberId=1, reservationId=10)");

      byte[] result = reservationService.getReservationQrCode(1L, 10L);

      log.info("[getReservationQrCode.success] expectedLength=3 actualLength={}", result.length);

      assertThat(result).isEqualTo(fakeImage);
    }

    @Test
    @DisplayName("본인 예약이 아니면 예외 발생")
    void notOwned() {
      given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));

      log.info("[getReservationQrCode.notOwned] input(memberId=999, reservationId=10)");

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> reservationService.getReservationQrCode(999L, 10L));

      log.info(
          "[getReservationQrCode.notOwned] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
    }

    @Test
    @DisplayName("CONFIRMED 상태가 아니면 예외 발생")
    void invalidStatus() {
      given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation)); // PENDING

      log.info(
          "[getReservationQrCode.invalidStatus] input(memberId=1, reservationId=10, status=PENDING)");

      ServiceException exception =
          assertThrows(
              ServiceException.class, () -> reservationService.getReservationQrCode(1L, 10L));

      log.info(
          "[getReservationQrCode.invalidStatus] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.INVALID_RESERVATION_STATUS,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
    }
  }

  @Nested
  @DisplayName("checkIn 검증")
  class CheckIn {

    @Test
    @DisplayName("정상 체크인 처리")
    void success() {
      reservation.confirm(true);
      // CheckInRequest는 테스트용 생성자가 있으므로 실제 객체 사용
      CheckInRequest request = new CheckInRequest("R20260912TEST0001");
      given(reservationRepository.findByReservationNumberWithPessimisticLock("R20260912TEST0001"))
          .willReturn(Optional.of(reservation));

      log.info("[checkIn.success] input(reservationNumber=R20260912TEST0001)");

      CheckInResponse response = reservationService.checkIn(request);

      log.info(
          "[checkIn.success] expectedStatus=USED actualStatus={} responseReservationId={}",
          reservation.getStatus(),
          response.getReservationId());

      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
    }

    @Test
    @DisplayName("예약번호에 해당하는 예약이 없으면 예외 발생")
    void notFound() {
      CheckInRequest request = new CheckInRequest("NOT_EXIST");
      given(reservationRepository.findByReservationNumberWithPessimisticLock("NOT_EXIST"))
          .willReturn(Optional.empty());

      log.info("[checkIn.notFound] input(reservationNumber=NOT_EXIST)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.checkIn(request));

      log.info(
          "[checkIn.notFound] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.RESERVATION_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }
  }

  @Nested
  @DisplayName("cancel 검증")
  class Cancel {

    @Test
    @DisplayName("본인 예약이면 cancelManager에 위임한다")
    void success() {
      given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));

      log.info("[cancel.success] input(memberId=1, reservationId=10)");

      reservationService.cancel(1L, 10L);

      log.info("[cancel.success] verify reservationCancelManager.cancel(10L) called");

      verify(reservationCancelManager, times(1)).cancel(10L);
    }

    @Test
    @DisplayName("본인 예약이 아니면 예외 발생, cancelManager는 호출되지 않는다")
    void notOwned() {
      given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));

      log.info("[cancel.notOwned] input(memberId=999, reservationId=10)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.cancel(999L, 10L));

      log.info(
          "[cancel.notOwned] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS,
          exception.getErrorCode());

      assertThat(exception.getErrorCode())
          .isEqualTo(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS);
      verify(reservationCancelManager, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("예약이 존재하지 않으면 예외 발생")
    void notFound() {
      given(reservationRepository.findById(999L)).willReturn(Optional.empty());

      log.info("[cancel.notFound] input(memberId=1, reservationId=999)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.cancel(1L, 999L));

      log.info(
          "[cancel.notFound] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.RESERVATION_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }
  }

  @Nested
  @DisplayName("expirePastReservation 검증")
  class ExpirePastReservation {

    @Test
    @DisplayName("만료 대상이 없으면 아무 처리도 하지 않는다")
    void empty() {
      given(reservationRepository.findExpiredReservations(any(), any())).willReturn(List.of());

      log.info("[expirePastReservation.empty] input(expiredCount=0)");

      reservationService.expirePastReservation();

      log.info("[expirePastReservation.empty] verify reservationCancelManager.expire never called");

      verify(reservationCancelManager, never()).expireNoShow(anyLong());
    }

    @Test
    @DisplayName("만료 대상이 있으면 각 건에 대해 cancelManager.expire를 호출한다")
    void withTargets() {
      Reservation another = Reservation.createReservation("R2", member, schedule, 1);
      ReflectionTestUtils.setField(another, "id", 11L);
      given(reservationRepository.findExpiredReservations(any(), any()))
          .willReturn(List.of(reservation, another));

      log.info("[expirePastReservation.withTargets] input(expiredCount=2)");

      reservationService.expirePastReservation();

      log.info("[expirePastReservation.withTargets] verify expire called for id=10 and id=11");

      verify(reservationCancelManager, times(1)).expireNoShow(10L);
      verify(reservationCancelManager, times(1)).expireNoShow(11L);
    }
  }

  @Nested
  @DisplayName("allReservations 검증")
  class AllReservations {

    @Test
    @DisplayName("본인 예약 목록을 ReservationResponse로 변환해 반환한다")
    void success() {
      given(reservationRepository.getAllReservation(1L)).willReturn(List.of(reservation));

      log.info("[allReservations.success] input(memberId=1)");

      List<ReservationResponse> result = reservationService.allReservations(1L);

      log.info("[allReservations.success] expectedSize=1 actualSize={}", result.size());

      assertThat(result).hasSize(1);
      assertThat(result.get(0).getReservationNumber()).isEqualTo("R20260912TEST0001");
    }

    @Test
    @DisplayName("예약이 없으면 빈 리스트를 반환한다")
    void empty() {
      given(reservationRepository.getAllReservation(1L)).willReturn(List.of());

      log.info("[allReservations.empty] input(memberId=1)");

      List<ReservationResponse> result = reservationService.allReservations(1L);

      log.info("[allReservations.empty] expectedSize=0 actualSize={}", result.size());

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("oneReservation 검증")
  class OneReservation {

    @Test
    @DisplayName("본인 예약이면 ReservationResponse를 반환한다")
    void success() {
      given(reservationRepository.findByIdAndMemberId(10L, 1L))
          .willReturn(Optional.of(reservation));

      log.info("[oneReservation.success] input(memberId=1, reservationId=10)");

      ReservationResponse result = reservationService.oneReservation(1L, 10L);

      log.info(
          "[oneReservation.success] expectedReservationId=10 actualReservationId={}",
          result.getReservationId());

      assertThat(result.getReservationId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("본인 소유가 아니거나 존재하지 않으면 예외 발생")
    void notFound() {
      given(reservationRepository.findByIdAndMemberId(10L, 999L)).willReturn(Optional.empty());

      log.info("[oneReservation.notFound] input(memberId=999, reservationId=10)");

      ServiceException exception =
          assertThrows(ServiceException.class, () -> reservationService.oneReservation(999L, 10L));

      log.info(
          "[oneReservation.notFound] expectedErrorCode={} actualErrorCode={}",
          ReservationErrorCode.RESERVATION_NOT_FOUND,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }
  }

  @Nested
  @DisplayName("getAdminReservations 검증")
  class GetAdminReservations {

    @Test
    @DisplayName("조건에 맞는 예약 목록을 AdminReservationResponse로 변환해 반환한다")
    void success() {
      given(
              reservationRepository.findAdminReservations(
                  1L, LocalDate.of(2026, 9, 20), ReservationStatus.CONFIRMED))
          .willReturn(List.of(reservation));

      log.info(
          "[getAdminReservations.success] input(popupId=1, date=2026-09-20, status=CONFIRMED)");

      List<AdminReservationResponse> result =
          reservationService.getAdminReservations(
              1L, LocalDate.of(2026, 9, 20), ReservationStatus.CONFIRMED);

      log.info("[getAdminReservations.success] expectedSize=1 actualSize={}", result.size());

      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("필터 조건이 모두 null이어도 정상 동작한다")
    void nullFilters() {
      given(reservationRepository.findAdminReservations(null, null, null)).willReturn(List.of());

      log.info("[getAdminReservations.nullFilters] input(popupId=null, date=null, status=null)");

      List<AdminReservationResponse> result =
          reservationService.getAdminReservations(null, null, null);

      log.info("[getAdminReservations.nullFilters] expectedSize=0 actualSize={}", result.size());

      assertThat(result).isEmpty();
    }
  }
}
