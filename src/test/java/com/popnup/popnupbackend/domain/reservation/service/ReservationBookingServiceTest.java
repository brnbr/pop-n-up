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
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ReservationBookingServiceTest {

  @InjectMocks private ReservationBookingService reservationBookingService;

  @Mock private ReservationRepository reservationRepository;
  @Mock private ScheduleRepository scheduleRepository;
  @Mock private MemberRepository memberRepository;
  @Mock private ScheduleCapacityCache scheduleCapacityCache;

  @Spy
  private ReservationNumberGenerator reservationNumberGenerator = new ReservationNumberGenerator();

  @Spy
  private TransactionTemplate transactionTemplate =
      new TransactionTemplate(mock(PlatformTransactionManager.class));

  @Nested
  @DisplayName("조건부 업데이트 기반 예약 생성 [book]")
  class BookTest {

    @Test
    @DisplayName("성공: 모든 검증 및 정원 증가 성공 시 예약이 생성되고 응답을 반환한다")
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
      given(reservationRepository.hasActiveReservation(scheduleId, memberId)).willReturn(false);
      given(scheduleCapacityCache.tryReserve(scheduleId, personCount)).willReturn(true);
      given(scheduleRepository.findWithPopupById(scheduleId)).willReturn(Optional.of(schedule));
      given(scheduleRepository.tryIncreaseCapacity(scheduleId, personCount)).willReturn(1);
      given(reservationRepository.save(any(Reservation.class))).willReturn(reservation);
      given(reservation.getId()).willReturn(100L);
      given(reservation.getReservationNumber()).willReturn("R20260915TEST");

      // when
      ReservationCreateResponse response = reservationBookingService.book(memberId, request);

      // then
      assertThat(response.getReservationId()).isEqualTo(100L);
      assertThat(response.getReservationNumber()).isEqualTo("R20260915TEST");

      verify(schedule).validateBookable(any(LocalDateTime.class));
      verify(reservationRepository).save(any(Reservation.class));
      verify(scheduleCapacityCache, never()).releaseNow(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: 정지된 회원이면 MEMBER_NOT_VALIDATE_ACTIVE 예외가 발생하고 정원을 건드리지 않는다")
    void book_inactiveMember() {
      // given
      Long memberId = 1L;
      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", 10L);
      ReflectionTestUtils.setField(request, "personCount", 1);

      Member member = Member.createLocal("suspended@test.com", "pw", "정지회원");
      member.suspend();
      given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

      // when & then
      assertThatThrownBy(() -> reservationBookingService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(MemberErrorCode.MEMBER_NOT_VALIDATE_ACTIVE));

      verify(reservationRepository, never()).hasActiveReservation(anyLong(), anyLong());
      verify(scheduleCapacityCache, never()).tryReserve(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: 이미 활성 예약이 존재하면 DUPLICATE_USER_RESERVATION 예외가 발생한다")
    void book_duplicateReservation() {
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
      assertThatThrownBy(() -> reservationBookingService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ReservationErrorCode.DUPLICATE_USER_RESERVATION));

      verify(scheduleCapacityCache, never()).tryReserve(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: Redis 캐시 차단 시 SCHEDULE_CAPACITY_EXCEEDED 예외가 발생하며 보상 트랜잭션은 호출되지 않는다")
    void book_redisCacheExceeded() {
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
      assertThatThrownBy(() -> reservationBookingService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED));

      verify(scheduleCapacityCache, never()).releaseNow(anyLong(), anyInt());
    }

    @Test
    @DisplayName("실패: DB 정원 증가 실패 시 SCHEDULE_CAPACITY_EXCEEDED 예외가 발생하고 Redis 보상 메서드가 호출된다")
    void book_dbCapacityExceeded_triggersCompensate() {
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
      given(scheduleRepository.findWithPopupById(scheduleId)).willReturn(Optional.of(schedule));
      given(scheduleRepository.tryIncreaseCapacity(scheduleId, personCount)).willReturn(0);

      // when & then
      assertThatThrownBy(() -> reservationBookingService.book(memberId, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED));

      verify(scheduleCapacityCache).releaseNow(scheduleId, personCount);
      verify(reservationRepository, never()).save(any(Reservation.class));
    }
  }

  @Nested
  @DisplayName("정책 검증 [R5 인원 상한]")
  class PersonCountPolicyTest {

    private ServiceException failure(Runnable action) {
      try {
        action.run();
      } catch (ServiceException e) {
        return e;
      }
      throw new AssertionError("ServiceException이 발생해야 합니다");
    }

    @Test
    @DisplayName("R5 실패: 예약 인원이 상한(10명)을 넘으면 DB·Redis를 건드리지 않고 거절한다")
    void book_personCountOverLimit_rejected() {
      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", 1L);
      ReflectionTestUtils.setField(request, "personCount", 11);

      ServiceException e = failure(() -> reservationBookingService.book(1L, request));

      assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_PERSON_COUNT);
      verifyNoInteractions(memberRepository, scheduleCapacityCache, scheduleRepository);
    }

    @Test
    @DisplayName("R5 경계: 10명은 인원 검증을 통과해 다음 단계(회원 조회)로 진행한다")
    void book_personCountAtLimit_passesValidation() {
      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", 1L);
      ReflectionTestUtils.setField(request, "personCount", 10);
      given(memberRepository.findById(1L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> reservationBookingService.book(1L, request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isNotEqualTo(ReservationErrorCode.INVALID_PERSON_COUNT));
      verify(memberRepository).findById(1L);
    }
  }

  @Nested
  @DisplayName("정원 마감 선확인 (트랜잭션 시작 전 거절)")
  class SoldOutPrecheckTest {

    private ReservationCreateRequest request(int personCount) {
      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", 1L);
      ReflectionTestUtils.setField(request, "personCount", personCount);
      return request;
    }

    @Test
    @DisplayName("마감이면 트랜잭션을 시작하기 전에 정원 초과로 거절한다 (DB 커넥션·쿼리 없음)")
    void soldOut_rejectedBeforeTransaction() {
      ReflectionTestUtils.setField(reservationBookingService, "soldOutPrecheck", true);
      given(scheduleCapacityCache.isSoldOut(1L, 1)).willReturn(true);

      assertThatThrownBy(() -> reservationBookingService.book(1L, request(1)))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_CAPACITY_EXCEEDED));

      // 트랜잭션이 시작되지 않았으므로 어떤 DB 접근도, Redis 카운터 증가도 없다
      verify(transactionTemplate, never()).execute(any());
      verifyNoInteractions(memberRepository, reservationRepository, scheduleRepository);
      verify(scheduleCapacityCache, never()).tryReserve(anyLong(), anyInt());
    }
  }
}
