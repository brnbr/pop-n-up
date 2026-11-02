package com.popnup.popnupbackend.domain.reservation.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Entry;
import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Outcome;
import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Result;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.global.config.JpaConfig;
import com.popnup.popnupbackend.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@DataJpaTest
@Import({QuerydslConfig.class, JpaConfig.class})
class QueueReservationPersisterTest {

  @Autowired private EntityManager em;
  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private DataSource dataSource;

  private NamedParameterJdbcTemplate jdbc;
  private QueueReservationPersister persister;
  private Popup popup;
  private long seq;

  @BeforeEach
  void setUp() {
    jdbc = new NamedParameterJdbcTemplate(dataSource);
    persister = new QueueReservationPersister(jdbc, scheduleRepository);
    popup = popup(PopupStatus.OPEN);
  }

  private Popup popup(PopupStatus status) {
    Popup p =
        Popup.builder()
            .title("대기열 팝업")
            .category(PopupCategory.ETC)
            .region("서울")
            .address("서울 성동구")
            .startDate(LocalDate.now().minusDays(1))
            .endDate(LocalDate.now().plusDays(30))
            .status(status)
            .build();
    em.persist(p);
    return p;
  }

  private Schedule schedule(Popup p, int max) {
    Schedule s =
        Schedule.createSchedule(
            p, LocalDate.now().plusDays(3), LocalTime.of(10, 0), LocalTime.of(11, 0), max);
    em.persist(s);
    return s;
  }

  private Member member(boolean active) {
    Member m = Member.createLocal("queue" + (++seq) + "@test.com", "pw", "회원");
    if (!active) {
      m.suspend();
    }
    em.persist(m);
    return m;
  }

  private Entry entry(Schedule s, Member m, int pc, long seqNo, long allocatedAtMs) {
    return new Entry(
        "0-" + seqNo,
        s.getId(),
        m.getId(),
        pc,
        "RQ" + s.getId() + "-" + seqNo,
        seqNo,
        allocatedAtMs);
  }

  private int nowCapacity(Schedule s) {
    return jdbc.queryForObject(
        "SELECT now_capacity FROM schedules WHERE id = :id",
        Map.of("id", s.getId()),
        Integer.class);
  }

  private int reservationCount(Schedule s) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM reservations WHERE schedule_id = :id",
        Map.of("id", s.getId()),
        Integer.class);
  }

  @Test
  @DisplayName("묶음을 한 번에 저장하고 회차 사용 좌석은 합계로 한 번 올린다. 생성 시각은 Redis에서 확정한 시각이다")
  void persist_batch() {
    Schedule s = schedule(popup, 10);
    Member a = member(true);
    Member b = member(true);
    em.flush();
    long allocatedAt = System.currentTimeMillis() - 5_000;

    List<Outcome> outcomes =
        persister.persist(List.of(entry(s, b, 1, 2, allocatedAt), entry(s, a, 2, 1, allocatedAt)));

    assertThat(outcomes)
        .extracting(o -> o.entry().seq(), Outcome::result)
        .containsExactly(tuple(1L, Result.PERSISTED), tuple(2L, Result.PERSISTED));
    assertThat(outcomes).allSatisfy(o -> assertThat(o.reservationId()).isNotNull());
    assertThat(nowCapacity(s)).isEqualTo(3);

    em.clear();
    Reservation saved = em.find(Reservation.class, outcomes.get(0).reservationId());
    assertThat(saved.getReservationNumber()).isEqualTo("RQ" + s.getId() + "-1");
    assertThat(saved.getPersonCount()).isEqualTo(2);
    assertThat(saved.getActiveMemberId()).isEqualTo(a.getId());
    assertThat(saved.getCreatedAt().truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(
            LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(allocatedAt), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS));
  }

  @Test
  @DisplayName("같은 신청을 다시 받으면(확인 응답 전에 작업자가 죽음) 다시 넣지 않고 처음 저장한 id를 돌려준다")
  void persist_idempotent() {
    Schedule s = schedule(popup, 10);
    Member a = member(true);
    em.flush();
    List<Entry> batch = List.of(entry(s, a, 2, 1, System.currentTimeMillis()));

    Long first = persister.persist(batch).get(0).reservationId();
    Outcome again = persister.persist(batch).get(0);

    assertThat(again.result()).isEqualTo(Result.PERSISTED);
    assertThat(again.reservationId()).isEqualTo(first);
    assertThat(nowCapacity(s)).isEqualTo(2);
    assertThat(reservationCount(s)).isEqualTo(1);
  }

  @Test
  @DisplayName("진행 중 예약이 있는 회원은 중복, 정지 회원은 실패이고 좌석을 쓰지 않는다")
  void persist_duplicateAndSuspended() {
    Schedule s = schedule(popup, 10);
    Member booked = member(true);
    Member suspended = member(false);
    Member ok = member(true);
    em.persist(Reservation.createReservation("R-EXISTING", booked, s, 1));
    em.flush();
    long now = System.currentTimeMillis();

    List<Outcome> outcomes =
        persister.persist(
            List.of(
                entry(s, booked, 1, 1, now),
                entry(s, suspended, 1, 2, now),
                entry(s, ok, 1, 3, now)));

    assertThat(outcomes)
        .extracting(o -> o.entry().seq(), Outcome::result)
        .containsExactlyInAnyOrder(
            tuple(1L, Result.DUPLICATE), tuple(2L, Result.FAILED), tuple(3L, Result.PERSISTED));
    assertThat(nowCapacity(s)).isEqualTo(1); // 기존 예약은 now_capacity를 올리지 않고 넣었으므로 새 1명만
  }

  @Test
  @DisplayName("DB 정원보다 많이 확정돼 왔으면 번호 순서대로 남은 좌석만큼만 저장하고 나머지는 매진이다")
  void persist_dbCapacityShort() {
    Schedule s = schedule(popup, 3);
    Member a = member(true);
    Member b = member(true);
    Member c = member(true);
    em.flush();
    long now = System.currentTimeMillis();

    List<Outcome> outcomes =
        persister.persist(
            List.of(entry(s, a, 2, 1, now), entry(s, b, 2, 2, now), entry(s, c, 1, 3, now)));

    assertThat(outcomes)
        .extracting(o -> o.entry().seq(), Outcome::result)
        .containsExactlyInAnyOrder(
            tuple(1L, Result.PERSISTED), tuple(2L, Result.SOLD_OUT), tuple(3L, Result.PERSISTED));
    assertThat(nowCapacity(s)).isEqualTo(3);
    assertThat(reservationCount(s)).isEqualTo(2);
  }

  @Test
  @DisplayName("예약할 수 없는 회차(팝업이 열리지 않음)면 모두 실패이고 좌석을 쓰지 않는다")
  void persist_notBookable() {
    Schedule s = schedule(popup(PopupStatus.CLOSED), 10);
    Member a = member(true);
    em.flush();

    Outcome outcome =
        persister.persist(List.of(entry(s, a, 1, 1, System.currentTimeMillis()))).get(0);

    assertThat(outcome.result()).isEqualTo(Result.FAILED);
    assertThat(nowCapacity(s)).isZero();
  }
}
