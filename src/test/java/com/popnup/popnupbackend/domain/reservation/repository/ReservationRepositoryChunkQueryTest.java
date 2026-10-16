package com.popnup.popnupbackend.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.config.JpaConfig;
import com.popnup.popnupbackend.global.config.QuerydslConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({QuerydslConfig.class, JpaConfig.class})
class ReservationRepositoryChunkQueryTest {

  private static final LocalTime TEN = LocalTime.of(10, 0);
  private static final LocalTime ELEVEN = LocalTime.of(11, 0);

  @Autowired private ReservationRepository reservationRepository;
  @Autowired private TestEntityManager em;

  private Popup popup;
  private int sequence;

  @BeforeEach
  void setUp() {
    popup =
        em.persist(
            Popup.builder()
                .title("테스트 팝업")
                .category(PopupCategory.ETC)
                .region("서울")
                .address("서울 성동구")
                .startDate(LocalDate.now().minusYears(1))
                .endDate(LocalDate.now().plusYears(1))
                .build());
  }

  private Schedule schedule(LocalDate date) {
    return em.persist(Schedule.createSchedule(popup, date, TEN, ELEVEN, 10));
  }

  private Reservation reservation(Schedule schedule, boolean confirmed) {
    sequence++;
    Member member = em.persist(Member.createLocal("user" + sequence + "@test.com", "pw", "회원"));
    Reservation reservation =
        Reservation.createReservation("R-TEST-" + sequence, member, schedule, 1);
    if (confirmed) {
      reservation.confirm(true);
    }
    return em.persist(reservation);
  }

  @Test
  @DisplayName("결제 대기 만료 청크: 기준 시각 이전의 PENDING만, id 오름차순으로, 청크 크기만큼 조회한다")
  void findPendingReservationsChunk() {
    Schedule schedule = schedule(LocalDate.now().plusDays(3));
    Reservation first = reservation(schedule, false);
    Reservation second = reservation(schedule, false);
    reservation(schedule, false);
    reservation(schedule, true);
    em.flush();
    em.clear();

    LocalDateTime deadline = LocalDateTime.now().plusMinutes(1);
    List<Reservation> chunk =
        reservationRepository.findPendingReservationsChunk(ReservationStatus.PENDING, deadline, 2);

    assertThat(chunk).extracting(Reservation::getId).containsExactly(first.getId(), second.getId());
    assertThat(
            reservationRepository.findPendingReservationsChunk(
                ReservationStatus.PENDING, LocalDateTime.now().minusHours(1), 10))
        .isEmpty();
  }

  @Test
  @DisplayName("결제 대기 만료 잠금 청크: 같은 조건으로 조회된다")
  void findPendingReservationsChunkForUpdate() {
    Schedule schedule = schedule(LocalDate.now().plusDays(3));
    Reservation first = reservation(schedule, false);
    Reservation second = reservation(schedule, false);
    reservation(schedule, true);
    em.flush();
    em.clear();

    List<Reservation> chunk =
        reservationRepository.findPendingReservationsChunkForUpdate(
            ReservationStatus.PENDING, LocalDateTime.now().plusMinutes(1), 10);

    assertThat(chunk).extracting(Reservation::getId).containsExactly(first.getId(), second.getId());
  }

  @Test
  @DisplayName("미방문 만료 청크: 지난 회차의 CONFIRMED만 조회한다 (미래 회차, PENDING 제외)")
  void findExpiredReservationsChunk() {
    Schedule past = schedule(LocalDate.now().minusDays(1));
    Schedule future = schedule(LocalDate.now().plusDays(1));
    Reservation expiredFirst = reservation(past, true);
    Reservation expiredSecond = reservation(past, true);
    reservation(future, true);
    reservation(past, false);
    em.flush();
    em.clear();

    List<Reservation> chunk =
        reservationRepository.findExpiredReservationsChunk(LocalDate.now(), LocalTime.now(), 10);

    assertThat(chunk)
        .extracting(Reservation::getId)
        .containsExactly(expiredFirst.getId(), expiredSecond.getId());
    assertThat(
            reservationRepository.findExpiredReservationsChunk(LocalDate.now(), LocalTime.now(), 1))
        .hasSize(1);
  }

  @Test
  @DisplayName("미방문 만료 잠금 청크: 같은 조건으로 조회된다")
  void findExpiredReservationsChunkForUpdate() {
    Schedule past = schedule(LocalDate.now().minusDays(1));
    Schedule future = schedule(LocalDate.now().plusDays(1));
    Reservation expired = reservation(past, true);
    reservation(future, true);
    em.flush();
    em.clear();

    List<Reservation> chunk =
        reservationRepository.findExpiredReservationsChunkForUpdate(
            LocalDate.now(), LocalTime.now(), 10);

    assertThat(chunk).extracting(Reservation::getId).containsExactly(expired.getId());
  }

  @Test
  @DisplayName("비관적 락 조회: id, 예약번호로 예약을 조회한다")
  void findWithPessimisticLock() {
    Schedule schedule = schedule(LocalDate.now().plusDays(3));
    Reservation saved = reservation(schedule, true);
    em.flush();
    em.clear();

    assertThat(reservationRepository.findByIdWithPessimisticLock(saved.getId())).isPresent();
    assertThat(
            reservationRepository.findByReservationNumberWithPessimisticLock(
                saved.getReservationNumber()))
        .isPresent();
    assertThat(reservationRepository.findByIdWithPessimisticLock(-1L)).isEmpty();
  }
}
