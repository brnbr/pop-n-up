package com.popnup.popnupbackend.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.config.JpaConfig;
import com.popnup.popnupbackend.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// SKIP LOCKED는 MySQL에서만 확인한다 (기본 테스트 DB인 H2에서는 건너뜀)
@DataJpaTest
@Import({QuerydslConfig.class, JpaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationClaimSkipLockedTest {

  @Autowired private ReservationRepository reservationRepository;
  @Autowired private EntityManager em;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  private TransactionTemplate tx;
  private final List<Object> created = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assumeTrue(connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL"));
    }
    tx = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanUp() {
    if (tx == null) {
      return;
    }
    tx.executeWithoutResult(
        status -> {
          for (int i = created.size() - 1; i >= 0; i--) {
            em.remove(em.merge(created.get(i)));
          }
        });
  }

  private <T> T persist(T entity) {
    em.persist(entity);
    created.add(entity);
    return entity;
  }

  @Test
  @DisplayName("다른 트랜잭션이 잠근 결제 대기 예약은 기다리지 않고 건너뛰어, 두 워커가 같은 예약을 가져가지 않는다")
  void secondClaimSkipsRowsLockedByFirst() throws Exception {
    List<Long> ids =
        tx.execute(
            status -> {
              Popup popup =
                  persist(
                      Popup.builder()
                          .title("잠금 팝업")
                          .category(PopupCategory.ETC)
                          .region("서울")
                          .address("서울 성동구")
                          .startDate(LocalDate.now().minusYears(1))
                          .endDate(LocalDate.now().plusYears(1))
                          .build());
              Schedule schedule =
                  persist(
                      Schedule.createSchedule(
                          popup,
                          LocalDate.now().plusDays(3),
                          LocalTime.of(10, 0),
                          LocalTime.of(11, 0),
                          10));
              List<Long> result = new ArrayList<>();
              for (int i = 0; i < 4; i++) {
                Member member = persist(Member.createLocal("lock" + i + "@test.com", "pw", "회원"));
                result.add(
                    persist(Reservation.createReservation("R-LOCK-" + i, member, schedule, 1))
                        .getId());
              }
              return result;
            });
    LocalDateTime deadline = LocalDateTime.now().plusMinutes(1);

    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    CompletableFuture<List<Long>> first =
        CompletableFuture.supplyAsync(
            () ->
                tx.execute(
                    status -> {
                      List<Long> claimed = claimIds(deadline, 2);
                      firstLocked.countDown();
                      await(secondDone);
                      return claimed;
                    }));
    await(firstLocked);
    List<Long> second = tx.execute(status -> claimIds(deadline, 4));
    secondDone.countDown();

    List<Long> firstClaimed = first.get(10, TimeUnit.SECONDS);
    assertThat(firstClaimed).containsExactly(ids.get(0), ids.get(1));
    assertThat(second).containsExactly(ids.get(2), ids.get(3));
  }

  private List<Long> claimIds(LocalDateTime deadline, int chunkSize) {
    return reservationRepository.claimExpiredPendingChunk(deadline, List.of(), chunkSize).stream()
        .map(Reservation::getId)
        .toList();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
