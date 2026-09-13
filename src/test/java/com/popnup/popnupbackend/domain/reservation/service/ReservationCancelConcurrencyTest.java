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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
class ReservationCancelConcurrencyTest extends ConcurrencyTestSupport {

  @Autowired private MemberRepository memberRepository;
  @Autowired private PopupRepository popupRepository;
  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private ReservationCancelManager reservationCancelManager;

  @Test
  @DisplayName("같은 예약에 대해 동시에 취소 요청 10번을 보내도 좌석은 딱 한 번만 복구된다")
  void concurrentCancel_onlyRestoresCapacityOnce() throws InterruptedException {
    // given: 실제 DB에 회원, 팝업, 스케줄, 예약을 저장 (초기 nowCapacity=2)
    Member member =
        memberRepository.save(Member.createLocal("concurrency-cancel@test.com", "pw", "테스터"));

    Popup popup =
        popupRepository.save(
            Popup.builder()
                .title("동시성 테스트 팝업")
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
    schedule.addReservation(2, LocalDateTime.now());
    scheduleRepository.save(schedule);

    Reservation reservation =
        reservationRepository.save(
            Reservation.createReservation("R-CONCURRENCY-CANCEL-1", member, schedule, 2));

    Long reservationId = reservation.getId();
    Long scheduleId = schedule.getId();

    log.info(
        "[concurrentCancel] setup(reservationId={}, scheduleId={}, initialNowCapacity={})",
        reservationId,
        scheduleId,
        schedule.getNowCapacity());

    // when: 10개 스레드가 동시에 같은 reservationId로 cancel() 호출
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch readyLatch = new CountDownLatch(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            readyLatch.countDown();
            try {
              startLatch.await(); // 모든 스레드가 동시에 출발하도록 대기
              reservationCancelManager.cancel(reservationId);
            } catch (Exception e) {
              log.info("[concurrentCancel] thread exception (기대된 상황일 수 있음): {}", e.getMessage());
            } finally {
              doneLatch.countDown();
            }
          });
    }

    readyLatch.await(); // 모든 스레드가 준비될 때까지 대기
    startLatch.countDown(); // 동시에 출발 신호
    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    // then: DB에서 다시 조회해서 최종 상태 확인
    Reservation finalReservation = reservationRepository.findById(reservationId).orElseThrow();
    Schedule finalSchedule = scheduleRepository.findById(scheduleId).orElseThrow();

    log.info(
        "[concurrentCancel] completed={} expectedStatus=CANCELED actualStatus={} expectedCapacity=0 actualCapacity={}",
        completed,
        finalReservation.getStatus(),
        finalSchedule.getNowCapacity());

    assertThat(completed).isTrue();
    assertThat(finalReservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    // 0번 요청이 몰려도 좌석은 정확히 1번만 복구되어야 함 (2 -> 0, 음수나 중복 복구 없이)
    assertThat(finalSchedule.getNowCapacity()).isZero();
  }
}
