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
import java.util.ArrayList;
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
      reservation.confirm();
    }
    return em.persist(reservation);
  }

  @Test
  @DisplayName("진행 중 예약 확인: active_member_id로 판단해 PENDING·CONFIRMED·USED는 진행 중이고, 취소되면 진행 중이 아니다")
  void hasActiveReservation() {
    Schedule schedule = schedule(LocalDate.now().plusDays(3));
    Schedule otherSchedule = schedule(LocalDate.now().plusDays(4));
    Reservation pending = reservation(schedule, false);
    Reservation confirmed = reservation(schedule, true);
    Reservation used = reservation(schedule, true);
    Reservation canceled = reservation(schedule, true);
    em.flush();
    reservationRepository.tryUpdateStatus(
        used.getId(), ReservationStatus.USED, List.of(ReservationStatus.CONFIRMED));
    reservationRepository.tryUpdateStatus(
        canceled.getId(), ReservationStatus.CANCELED, List.of(ReservationStatus.CONFIRMED));
    em.clear();

    for (Reservation active : List.of(pending, confirmed, used)) {
      assertThat(
              reservationRepository.hasActiveReservation(
                  schedule.getId(), active.getMember().getId()))
          .isTrue();
    }
    assertThat(
            reservationRepository.hasActiveReservation(
                schedule.getId(), canceled.getMember().getId()))
        .isFalse();
    assertThat(
            reservationRepository.hasActiveReservation(
                otherSchedule.getId(), pending.getMember().getId()))
        .isFalse();
  }

  @Test
  @DisplayName("결제 대기 만료 청크: 기준 시각 이전의 PENDING만, 커서(id) 뒤부터 id 오름차순으로, 청크 크기만큼 조회한다")
  void findPendingReservationsChunk() {
    Schedule schedule = schedule(LocalDate.now().plusDays(3));
    Reservation first = reservation(schedule, false);
    Reservation second = reservation(schedule, false);
    Reservation third = reservation(schedule, false);
    reservation(schedule, true);
    em.flush();
    em.clear();

    LocalDateTime deadline = LocalDateTime.now().plusMinutes(1);
    List<Reservation> chunk =
        reservationRepository.findPendingReservationsChunk(
            ReservationStatus.PENDING, deadline, 0L, 2);
    List<Reservation> afterFirst =
        reservationRepository.findPendingReservationsChunk(
            ReservationStatus.PENDING, deadline, first.getId(), 10);

    assertThat(chunk).extracting(Reservation::getId).containsExactly(first.getId(), second.getId());
    assertThat(afterFirst)
        .extracting(Reservation::getId)
        .containsExactly(second.getId(), third.getId());
    assertThat(
            reservationRepository.findPendingReservationsChunk(
                ReservationStatus.PENDING, LocalDateTime.now().minusHours(1), 0L, 10))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "노쇼 만료 벌크: 기준 시각보다 먼저 시작한 회차의 CONFIRMED만 EXPIRED로 바꾸고 활성 슬롯을 비운다 (그 뒤 시작 회차, PENDING 제외)")
  void expirePastConfirmedReservations() {
    LocalDate today = LocalDate.now();
    Schedule yesterday = schedule(today.minusDays(1));
    Schedule todayAtTen = schedule(today);
    Schedule tomorrow = schedule(today.plusDays(1));
    Reservation expiredFirst = reservation(yesterday, true);
    Reservation expiredSecond = reservation(yesterday, true);
    Reservation todayConfirmed = reservation(todayAtTen, true);
    Reservation tomorrowConfirmed = reservation(tomorrow, true);
    Reservation yesterdayPending = reservation(yesterday, false);
    em.flush();
    em.clear();

    // 기준 시각이 시작 시각과 같으면 아직 만료하지 않는다
    long expired =
        reservationRepository.expirePastConfirmedReservations(LocalDateTime.of(today, TEN));
    em.clear();

    assertThat(expired).isEqualTo(2);
    for (Reservation target : List.of(expiredFirst, expiredSecond)) {
      Reservation found = em.find(Reservation.class, target.getId());
      assertThat(found.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
      assertThat(found.getActiveMemberId()).isNull();
    }
    assertThat(em.find(Reservation.class, todayConfirmed.getId()).getStatus())
        .isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(em.find(Reservation.class, tomorrowConfirmed.getId()).getStatus())
        .isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(em.find(Reservation.class, yesterdayPending.getId()).getStatus())
        .isEqualTo(ReservationStatus.PENDING);

    // 기준 시각이 시작 시각을 넘으면 오늘 회차도 만료한다
    assertThat(
            reservationRepository.expirePastConfirmedReservations(
                LocalDateTime.of(today, TEN.plusMinutes(1))))
        .isEqualTo(1);
    em.clear();
    assertThat(em.find(Reservation.class, todayConfirmed.getId()).getStatus())
        .isEqualTo(ReservationStatus.EXPIRED);
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

  @Test
  @DisplayName("관리자 예약 목록: 같은 회차의 예약도 페이지를 넘겨 조회하면 중복·누락 없이 id 순으로 이어진다")
  void findAdminReservations_stablePaging() {
    Schedule schedule = schedule(LocalDate.now().plusDays(3));
    List<Long> expected = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      expected.add(reservation(schedule, false).getId());
    }
    em.flush();
    em.clear();

    List<Long> paged = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      reservationRepository.findAdminReservations(popup.getId(), null, null, page, 2).stream()
          .map(Reservation::getId)
          .forEach(paged::add);
    }

    assertThat(paged).containsExactlyElementsOf(expected);
  }
}
