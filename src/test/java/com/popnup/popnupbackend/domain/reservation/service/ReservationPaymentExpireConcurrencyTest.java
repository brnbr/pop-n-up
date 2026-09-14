package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.support.ConcurrencyTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
class ReservationPaymentExpireConcurrencyTest extends ConcurrencyTestSupport {

  @Autowired private MemberRepository memberRepository;
  @Autowired private PopupRepository popupRepository;
  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private ReservationRepository reservationRepository;

  @Autowired private ReservationService reservationService;
  @Autowired private ReservationCancelManager reservationCancelManager;

  @Test
  @DisplayName("결제 승인과 예약 만료가 동시에 발생해도 예약 상태와 좌석 정합성을 유지한다")
  void concurrentConfirmAndExpire() throws InterruptedException {

    // given
    Member member =
        memberRepository.save(
            Member.createLocal("payment-expire-concurrency@test.com", "pw", "테스터"));

    Popup popup =
        popupRepository.save(
            Popup.builder()
                .title("결제 만료 동시성 테스트 팝업")
                .category(PopupCategory.ETC)
                .region("서울")
                .address("서울시 강남구")
                .startDate(LocalDate.now().minusDays(5))
                .endDate(LocalDate.now().plusDays(30))
                .isFree(false)
                .price(10000)
                .status(PopupStatus.OPEN)
                .build());

    Schedule schedule =
        scheduleRepository.save(
            Schedule.createSchedule(
                popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10));

    // 2명 예약
    schedule.addReservation(2, LocalDateTime.now());
    scheduleRepository.save(schedule);

    Reservation reservation =
        reservationRepository.save(
            Reservation.createReservation("R-PAYMENT-EXPIRE-CONCURRENCY-1", member, schedule, 2));

    Long reservationId = reservation.getId();
    Long scheduleId = schedule.getId();

    log.info(
        "[concurrentConfirmAndExpire] setup reservationId={}, " + "status={}, nowCapacity={}",
        reservationId,
        reservation.getStatus(),
        schedule.getNowCapacity());

    ExecutorService executor = Executors.newFixedThreadPool(2);

    CountDownLatch readyLatch = new CountDownLatch(2);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);

    AtomicInteger confirmSuccessCount = new AtomicInteger();
    AtomicInteger expireSuccessCount = new AtomicInteger();

    // 결제 승인 스레드
    executor.submit(
        () -> {
          readyLatch.countDown();

          try {
            startLatch.await();

            reservationService.confirmReservation(reservationId, true);

            confirmSuccessCount.incrementAndGet();

            log.info("[confirm] success");

          } catch (Exception e) {

            log.info("[confirm] failed: {}", e.getMessage());

          } finally {
            doneLatch.countDown();
          }
        });

    // 만료 스레드
    executor.submit(
        () -> {
          readyLatch.countDown();

          try {
            startLatch.await();

            reservationCancelManager.expire(reservationId);

            expireSuccessCount.incrementAndGet();

            log.info("[expire] success");

          } catch (Exception e) {

            log.info("[expire] failed: {}", e.getMessage());

          } finally {
            doneLatch.countDown();
          }
        });

    readyLatch.await();

    // 동시에 시작
    startLatch.countDown();

    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

    executor.shutdown();

    // then
    Reservation finalReservation = reservationRepository.findById(reservationId).orElseThrow();

    Schedule finalSchedule = scheduleRepository.findById(scheduleId).orElseThrow();

    log.info(
        "[result] completed={}, "
            + "confirmSuccess={}, "
            + "expireSuccess={}, "
            + "finalStatus={}, "
            + "finalCapacity={}",
        completed,
        confirmSuccessCount.get(),
        expireSuccessCount.get(),
        finalReservation.getStatus(),
        finalSchedule.getNowCapacity());

    assertThat(completed).isTrue();

    // 현재 구현에서는 expire가 최종적으로 수행되므로
    // 최종 상태는 EXPIRED가 될 가능성이 높음
    assertThat(finalReservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

    // 예약 당시 2명이 증가했으므로
    // 만료 처리 후에는 정확히 한 번만 복구되어야 함
    assertThat(finalSchedule.getNowCapacity()).isZero();
  }
}
