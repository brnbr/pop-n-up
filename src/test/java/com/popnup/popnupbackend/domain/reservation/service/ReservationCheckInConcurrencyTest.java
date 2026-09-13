package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.support.ConcurrencyTestSupport;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
class ReservationCheckInConcurrencyTest extends ConcurrencyTestSupport {

  @Autowired private MemberRepository memberRepository;
  @Autowired private PopupRepository popupRepository;
  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private ReservationService reservationService;

  @Test
  @DisplayName("같은 예약번호로 동시에 체크인 10번을 시도해도 딱 1번만 성공한다")
  void concurrentCheckIn_onlySucceedsOnce() throws InterruptedException {
    // given: CONFIRMED 상태의 예약을 실제 DB에 저장
    Member member =
        memberRepository.save(Member.createLocal("concurrency-checkin@test.com", "pw", "테스터"));

    Popup popup =
        popupRepository.save(
            Popup.builder()
                .title("동시성 체크인 테스트 팝업")
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
                popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10));

    Reservation reservation =
        reservationRepository.save(
            Reservation.createReservation("R-CONCURRENCY-CHECKIN-1", member, schedule, 2));
    reservation.confirm(true);
    reservationRepository.save(reservation);

    Long reservationId = reservation.getId();
    String reservationNumber = reservation.getReservationNumber();

    log.info(
        "[concurrentCheckIn] setup(reservationId={}, reservationNumber={}, initialStatus={})",
        reservationId,
        reservationNumber,
        reservation.getStatus());

    // when: 10개 스레드가 동시에 같은 reservationNumber로 checkIn() 호출
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch readyLatch = new CountDownLatch(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            readyLatch.countDown();
            try {
              startLatch.await();
              reservationService.checkIn(new CheckInRequest(reservationNumber));
              successCount.incrementAndGet();
            } catch (Exception e) {
              failCount.incrementAndGet();
              log.info("[concurrentCheckIn] thread failed (기대된 상황): {}", e.getMessage());
            } finally {
              doneLatch.countDown();
            }
          });
    }

    readyLatch.await();
    startLatch.countDown();
    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    // then: 성공은 정확히 1건, 나머지는 전부 실패해야 함
    Reservation finalReservation = reservationRepository.findById(reservationId).orElseThrow();

    log.info(
        "[concurrentCheckIn] completed={} expectedSuccessCount=1 actualSuccessCount={} actualFailCount={} finalStatus={}",
        completed,
        successCount.get(),
        failCount.get(),
        finalReservation.getStatus());

    assertThat(completed).isTrue();
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failCount.get()).isEqualTo(threadCount - 1);
    assertThat(finalReservation.getStatus()).isEqualTo(ReservationStatus.USED);
  }
}
