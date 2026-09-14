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
        "[concurrentConfirmAndExpire] setup reservationId={}, status={}, nowCapacity={}",
        reservationId,
        reservation.getStatus(),
        schedule.getNowCapacity());

    ExecutorService executor = Executors.newFixedThreadPool(2);

    CountDownLatch readyLatch = new CountDownLatch(2);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);

    AtomicInteger confirmSuccessCount = new AtomicInteger();
    AtomicInteger expireCompletedCount = new AtomicInteger();

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

    // 결제 타임아웃 스레드
    executor.submit(
        () -> {
          readyLatch.countDown();

          try {
            startLatch.await();

            reservationCancelManager.expirePaymentTimeout(reservationId);

            expireCompletedCount.incrementAndGet();

            log.info("[expirePaymentTimeout] completed");

          } catch (Exception e) {
            log.info("[expirePaymentTimeout] failed: {}", e.getMessage());

          } finally {
            doneLatch.countDown();
          }
        });

    readyLatch.await();

    // 두 스레드 동시에 시작
    startLatch.countDown();

    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

    executor.shutdown();

    // then
    Reservation finalReservation = reservationRepository.findById(reservationId).orElseThrow();

    Schedule finalSchedule = scheduleRepository.findById(scheduleId).orElseThrow();

    log.info(
        "[result] completed={}, confirmSuccess={}, expireCompleted={}, finalStatus={}, finalCapacity={}",
        completed,
        confirmSuccessCount.get(),
        expireCompletedCount.get(),
        finalReservation.getStatus(),
        finalSchedule.getNowCapacity());

    assertThat(completed).isTrue();

    /*
     * 두 결과 모두 가능하다.
     *
     * 1. 결제 승인 스레드가 먼저 락 획득
     *    PENDING -> CONFIRMED
     *    타임아웃은 CONFIRMED를 확인하고 아무 처리하지 않음
     *
     * 2. 타임아웃 스레드가 먼저 락 획득
     *    PENDING -> EXPIRED
     *    좌석 복구
     *    이후 결제 승인은 상태 검증에서 실패
     */

    if (finalReservation.getStatus() == ReservationStatus.CONFIRMED) {

      assertThat(confirmSuccessCount.get()).isEqualTo(1);

      // 결제가 확정됐으므로 예약 좌석 2명 유지
      assertThat(finalSchedule.getNowCapacity()).isEqualTo(2);

    } else {

      assertThat(finalReservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

      // 타임아웃으로 예약이 만료됐으므로 좌석 복구
      assertThat(finalSchedule.getNowCapacity()).isZero();

      // 만료가 먼저 처리됐다면 결제 승인은 성공할 수 없음
      assertThat(confirmSuccessCount.get()).isZero();
    }
  }
}
