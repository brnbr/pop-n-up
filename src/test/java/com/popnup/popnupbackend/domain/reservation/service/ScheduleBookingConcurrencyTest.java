package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.support.ConcurrencyTestSupport;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
class ScheduleBookingConcurrencyTest extends ConcurrencyTestSupport {

  @Autowired private MemberRepository memberRepository;
  @Autowired private PopupRepository popupRepository;
  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private ReservationService reservationService;

  @Test
  @DisplayName("정원 10명 스케줄에 100명이 동시 예약해도 정확히 10명만 성공한다")
  void concurrentBooking_neverExceedsCapacity() throws InterruptedException {
    // given: 정원 10명짜리 스케줄, 서로 다른 회원 100명
    int capacity = 10;
    int requesterCount = 100;

    Popup popup =
        popupRepository.save(
            Popup.builder()
                .title("동시성 예약 테스트 팝업")
                .category(PopupCategory.ETC)
                .region("서울")
                .address("서울시 강남구")
                .startDate(LocalDate.now().minusDays(5))
                .endDate(LocalDate.now().plusDays(30))
                .isFree(true)
                .price(0)
                .status(PopupStatus.OPEN)
                .build());

    Schedule schedule =
        scheduleRepository.save(
            Schedule.createSchedule(
                popup,
                LocalDate.now().plusDays(1),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                capacity));
    Long scheduleId = schedule.getId();

    List<Long> memberIds = new ArrayList<>();
    for (int i = 0; i < requesterCount; i++) {
      Member member =
          memberRepository.save(
              Member.createLocal("concurrency-booking-" + i + "@test.com", "pw", "테스터" + i));
      memberIds.add(member.getId());
    }

    log.info(
        "[concurrentBooking] setup(scheduleId={}, capacity={}, requesterCount={})",
        scheduleId,
        capacity,
        requesterCount);

    // when: 100명이 동시에 personCount=1로 예약 시도
    ExecutorService executor = Executors.newFixedThreadPool(requesterCount);
    CountDownLatch readyLatch = new CountDownLatch(requesterCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(requesterCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    for (Long memberId : memberIds) {
      executor.submit(
          () -> {
            readyLatch.countDown();
            try {
              startLatch.await();

              ReservationCreateRequest request = Mockito.mock(ReservationCreateRequest.class);
              Mockito.when(request.getScheduleId()).thenReturn(scheduleId);
              Mockito.when(request.getPersonCount()).thenReturn(1);

              reservationService.book(memberId, request);
              successCount.incrementAndGet();
            } catch (Exception e) {
              failCount.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    readyLatch.await();
    startLatch.countDown();
    boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
    executor.shutdown();

    // then: 성공은 정확히 capacity(10)건, 좌석은 정원을 절대 넘지 않아야 함
    Schedule finalSchedule = scheduleRepository.findById(scheduleId).orElseThrow();

    log.info(
        "[concurrentBooking] completed={} expectedSuccessCount={} actualSuccessCount={} actualFailCount={} finalNowCapacity={}",
        completed,
        capacity,
        successCount.get(),
        failCount.get(),
        finalSchedule.getNowCapacity());

    assertThat(completed).isTrue();
    assertThat(successCount.get()).isEqualTo(capacity);
    assertThat(failCount.get()).isEqualTo(requesterCount - capacity);
    // nowCapacity가 정원(10)을 절대 넘지 않음 - 이게 락이 실제로 막아주는지의 증거
    assertThat(finalSchedule.getNowCapacity()).isEqualTo(capacity);
    assertThat(finalSchedule.getNowCapacity()).isLessThanOrEqualTo(finalSchedule.getMaxCapacity());
  }
}
