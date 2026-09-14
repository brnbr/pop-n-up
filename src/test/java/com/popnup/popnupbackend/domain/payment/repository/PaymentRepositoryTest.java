package com.popnup.popnupbackend.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.payment.entity.Payment;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class PaymentRepositoryTest {

  @Autowired private PaymentRepository paymentRepository;

  @Autowired private EntityManager entityManager;

  @Autowired private PlatformTransactionManager transactionManager;

  /** 하나의 예약에 Payment를 2개 저장하려고 하면 DB의 UNIQUE 제약조건에 의해 두 번째 Payment 저장이 실패하는지 검증한다. */
  @Test
  void 하나의_예약에_Payment를_두개_생성할_수_없다() {

    Long reservationId =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  // 1. 회원 생성
                  Member member = Member.createLocal("test@test.com", "password", "테스트회원");

                  entityManager.persist(member);

                  // 2. 팝업 생성
                  Popup popup =
                      Popup.builder()
                          .title("테스트 팝업")
                          .description("테스트용 팝업")
                          .category(PopupCategory.FASHION)
                          .region("대구")
                          .address("대구광역시")
                          .startDate(LocalDate.now())
                          .endDate(LocalDate.now().plusDays(10))
                          .isFree(false)
                          .price(10000)
                          .status(PopupStatus.OPEN)
                          .build();

                  entityManager.persist(popup);

                  // 3. 스케줄 생성
                  Schedule schedule =
                      Schedule.createSchedule(
                          popup,
                          LocalDate.now().plusDays(1),
                          LocalTime.of(14, 0),
                          LocalTime.of(15, 0),
                          100);

                  entityManager.persist(schedule);

                  // 4. 예약 생성
                  Reservation reservation =
                      Reservation.createReservation("R20260914TEST", member, schedule, 1);

                  entityManager.persist(reservation);

                  entityManager.flush();

                  return reservation.getId();
                });

    // 5. 첫 번째 Payment 저장
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              Reservation reservation = entityManager.find(Reservation.class, reservationId);

              Payment payment1 = new Payment(reservation, "R20260914TEST", 10000);

              paymentRepository.save(payment1);

              entityManager.flush();

              return null;
            });

    // 6. 같은 예약으로 두 번째 Payment 저장 시도
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        status -> {
                          Reservation reservation =
                              entityManager.find(Reservation.class, reservationId);

                          Payment payment2 = new Payment(reservation, "R20260914TEST-2", 10000);

                          paymentRepository.save(payment2);

                          entityManager.flush();

                          return null;
                        }))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** 두 개의 스레드가 동시에 같은 예약에 Payment를 생성하려고 해도 DB UNIQUE 제약조건에 의해 Payment는 하나만 저장되는지 검증한다. */
  @Test
  void 동시에_Payment를_생성해도_하나만_저장된다() throws Exception {

    // 테스트용 Reservation을 먼저 DB에 저장한다.
    Long reservationId =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  // 1. 회원 생성
                  Member member = Member.createLocal("concurrent@test.com", "password", "동시성테스트회원");

                  entityManager.persist(member);

                  // 2. 팝업 생성
                  Popup popup =
                      Popup.builder()
                          .title("동시성 테스트 팝업")
                          .description("동시성 테스트용 팝업")
                          .category(PopupCategory.FASHION)
                          .region("대구")
                          .address("대구광역시")
                          .startDate(LocalDate.now())
                          .endDate(LocalDate.now().plusDays(10))
                          .isFree(false)
                          .price(10000)
                          .status(PopupStatus.OPEN)
                          .build();

                  entityManager.persist(popup);

                  // 3. 스케줄 생성
                  Schedule schedule =
                      Schedule.createSchedule(
                          popup,
                          LocalDate.now().plusDays(1),
                          LocalTime.of(14, 0),
                          LocalTime.of(15, 0),
                          100);

                  entityManager.persist(schedule);

                  // 4. 예약 생성
                  Reservation reservation =
                      Reservation.createReservation("R20260914-CONCURRENT", member, schedule, 1);

                  entityManager.persist(reservation);

                  entityManager.flush();

                  return reservation.getId();
                });

    int threadCount = 2;

    ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);

    List<Future<?>> futures = new ArrayList<>();

    // 두 스레드가 동시에 Payment INSERT를 시도한다.
    for (int i = 0; i < threadCount; i++) {

      futures.add(
          executorService.submit(
              () -> {

                // 모든 스레드가 준비될 때까지 대기
                ready.countDown();

                try {
                  ready.await();
                  start.await();

                  // 각 스레드는 독립적인 트랜잭션을 사용한다.
                  new TransactionTemplate(transactionManager)
                      .execute(
                          status -> {
                            Reservation reservation =
                                entityManager.find(Reservation.class, reservationId);

                            Payment payment =
                                new Payment(reservation, "R20260914-CONCURRENT", 10000);

                            paymentRepository.save(payment);

                            entityManager.flush();

                            return null;
                          });

                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }));
    }

    // 두 스레드가 모두 준비될 때까지 기다린다.
    ready.await();

    // 두 스레드를 동시에 출발시킨다.
    start.countDown();

    // 각 스레드의 결과를 기다린다.
    int successCount = 0;
    int failureCount = 0;

    for (Future<?> future : futures) {
      try {
        future.get();
        successCount++;
      } catch (ExecutionException e) {
        failureCount++;
      }
    }

    executorService.shutdown();

    // 정확히 하나는 성공하고 하나는 UNIQUE 제약조건으로 실패해야 한다.
    assertThat(successCount).isEqualTo(1);
    assertThat(failureCount).isEqualTo(1);

    // 최종적으로 Payment는 하나만 존재해야 한다.
    long paymentCount = paymentRepository.countByReservationId(reservationId);

    assertThat(paymentCount).isEqualTo(1L);
  }
}
