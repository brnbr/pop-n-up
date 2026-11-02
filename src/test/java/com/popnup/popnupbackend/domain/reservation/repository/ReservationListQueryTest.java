package com.popnup.popnupbackend.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.AdminCursor;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.MemberCursor;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.config.JpaConfig;
import com.popnup.popnupbackend.global.config.QuerydslConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

// 관리자 · 내 예약 목록: 예약에 복사한 회차 키(B3)로 거르고 정렬하는지, 키셋(B4)으로 끝까지 넘겨도 중복 · 누락이 없는지
@DataJpaTest
@Import({QuerydslConfig.class, JpaConfig.class})
class ReservationListQueryTest {

  @Autowired private ReservationRepository reservationRepository;
  @Autowired private TestEntityManager em;

  private Popup popup;
  private Popup otherPopup;
  private Member member;
  private final List<Reservation> popupReservations = new ArrayList<>();
  private int sequence;

  @BeforeEach
  void setUp() {
    popup = em.persist(popup("목록 팝업"));
    otherPopup = em.persist(popup("다른 팝업"));
    member = em.persist(Member.createLocal("owner@test.com", "pw", "주인"));
    LocalDate base = LocalDate.now().plusDays(10);
    // 날짜 3일 × 시간대 3개, 회차마다 예약 3건 (생성 순서와 정렬 순서가 다르도록 날짜를 거꾸로 만든다)
    for (int d = 2; d >= 0; d--) {
      for (int h = 12; h >= 10; h--) {
        Schedule schedule =
            em.persist(
                Schedule.createSchedule(
                    popup, base.plusDays(d), LocalTime.of(h, 0), LocalTime.of(h, 50), 10));
        for (int k = 0; k < 3; k++) {
          popupReservations.add(reservation(schedule, sequence % 4 == 0 ? member : newMember()));
        }
      }
    }
    Schedule other =
        em.persist(
            Schedule.createSchedule(
                otherPopup, base, LocalTime.of(10, 0), LocalTime.of(10, 50), 10));
    reservation(other, newMember());
    em.flush();
    em.clear();
  }

  private Popup popup(String title) {
    return Popup.builder()
        .title(title)
        .category(PopupCategory.ETC)
        .region("서울")
        .address("서울 성동구")
        .startDate(LocalDate.now().minusYears(1))
        .endDate(LocalDate.now().plusYears(1))
        .build();
  }

  private Member newMember() {
    return em.persist(Member.createLocal("m" + sequence + "@test.com", "pw", "회원"));
  }

  private Reservation reservation(Schedule schedule, Member owner) {
    sequence++;
    return em.persist(Reservation.createReservation("R-LIST-" + sequence, owner, schedule, 1));
  }

  private List<Long> expectedAdminOrder() {
    return popupReservations.stream()
        .sorted(
            Comparator.comparing(Reservation::getScheduleDate)
                .thenComparing(Reservation::getStartTime)
                .thenComparing(Reservation::getId))
        .map(Reservation::getId)
        .toList();
  }

  @Test
  @DisplayName("예약을 만들 때 회차의 팝업 · 날짜 · 시작 시각을 복사한다")
  void sortKeysCopied() {
    Reservation r = reservationRepository.findById(popupReservations.get(0).getId()).orElseThrow();

    assertThat(r.getPopupId()).isEqualTo(popup.getId());
    assertThat(r.getScheduleDate()).isEqualTo(r.getSchedule().getScheduleDate());
    assertThat(r.getStartTime()).isEqualTo(r.getSchedule().getStartTime());
  }

  @Test
  @DisplayName("관리자 목록 OFFSET: 팝업으로 거르고 (회차 날짜, 시작 시각, id) 순서")
  void admin_offset_order() {
    List<Long> page0 =
        ids(reservationRepository.findAdminReservations(popup.getId(), null, null, 0, 10));
    List<Long> page1 =
        ids(reservationRepository.findAdminReservations(popup.getId(), null, null, 1, 10));

    List<Long> expected = expectedAdminOrder();
    assertThat(page0).containsExactlyElementsOf(expected.subList(0, 10));
    assertThat(page1).containsExactlyElementsOf(expected.subList(10, 20));
  }

  @Test
  @DisplayName("관리자 목록 키셋: 마지막 행 다음부터 끝까지 넘기면 OFFSET 전체 순서와 같고 중복 · 누락이 없다")
  void admin_keyset_walk() {
    List<Long> walked = new ArrayList<>();
    AdminCursor cursor = null;
    while (true) {
      List<Reservation> page =
          reservationRepository.findAdminReservationsAfter(popup.getId(), null, null, cursor, 4);
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(ids(page));
      Reservation last = page.get(page.size() - 1);
      cursor = new AdminCursor(last.getScheduleDate(), last.getStartTime(), last.getId());
    }

    assertThat(walked).containsExactlyElementsOf(expectedAdminOrder());
  }

  @Test
  @DisplayName("내 예약 목록 키셋: (생성 시각, id) 역순으로 끝까지 중복 · 누락 없이 넘긴다")
  void member_keyset_walk() {
    List<Long> all = ids(reservationRepository.findMemberReservations(member.getId(), 0, 100));
    List<Long> walked = new ArrayList<>();
    MemberCursor cursor = null;
    while (true) {
      List<Reservation> page =
          reservationRepository.findMemberReservationsAfter(member.getId(), cursor, 2);
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(ids(page));
      Reservation last = page.get(page.size() - 1);
      cursor = new MemberCursor(last.getCreatedAt(), last.getId());
    }

    assertThat(all).isNotEmpty();
    assertThat(walked).containsExactlyElementsOf(all);
  }

  private static List<Long> ids(List<Reservation> rows) {
    return rows.stream().map(Reservation::getId).toList();
  }
}
