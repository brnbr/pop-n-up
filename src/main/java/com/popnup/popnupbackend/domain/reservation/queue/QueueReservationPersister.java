package com.popnup.popnupbackend.domain.reservation.queue;

import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class QueueReservationPersister {

  public record Entry(
      String recordId,
      long scheduleId,
      long memberId,
      int personCount,
      String reservationNumber,
      long seq,
      long allocatedAtMs) {}

  public enum Result {
    PERSISTED,
    DUPLICATE,
    // DB 정원이 모자람 (Redis 사용량이 DB보다 낮았음)
    SOLD_OUT,
    FAILED
  }

  public record Outcome(Entry entry, Result result, Long reservationId) {}

  private static final String INSERT =
      """
      INSERT INTO reservations (created_at, updated_at, reservation_number, member_id, schedule_id, person_count,
                                status, active_member_id, popup_id, schedule_date, start_time)
      VALUES (:createdAt, :updatedAt, :number, :memberId, :scheduleId, :personCount,
              'PENDING', :memberId, :popupId, :scheduleDate, :startTime)
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final ScheduleRepository scheduleRepository;

  @Transactional
  public List<Outcome> persist(List<Entry> entries) {
    Map<Long, List<Entry>> bySchedule = new LinkedHashMap<>();
    entries.stream()
        .sorted(Comparator.comparingLong(Entry::seq))
        .forEach(e -> bySchedule.computeIfAbsent(e.scheduleId(), k -> new ArrayList<>()).add(e));
    List<Outcome> outcomes = new ArrayList<>(entries.size());
    bySchedule.forEach((scheduleId, list) -> outcomes.addAll(persistSchedule(scheduleId, list)));
    return outcomes;
  }

  private List<Outcome> persistSchedule(long scheduleId, List<Entry> entries) {
    List<Outcome> outcomes = new ArrayList<>(entries.size());
    Map<String, Long> saved = idsByNumber(entries);
    List<Entry> pending = new ArrayList<>();
    for (Entry e : entries) {
      if (saved.containsKey(e.reservationNumber())) {
        outcomes.add(new Outcome(e, Result.PERSISTED, saved.get(e.reservationNumber())));
      } else {
        pending.add(e);
      }
    }
    if (pending.isEmpty()) {
      return outcomes;
    }

    Set<Long> duplicate = activeMembers(scheduleId, pending);
    Set<Long> active = activeMemberIds(pending);
    Schedule schedule = scheduleRepository.findWithPopupById(scheduleId).orElse(null);
    boolean bookable = schedule != null && isBookable(schedule);

    List<Entry> candidates = new ArrayList<>();
    for (Entry e : pending) {
      if (duplicate.contains(e.memberId())) {
        outcomes.add(new Outcome(e, Result.DUPLICATE, null));
      } else if (!bookable || !active.contains(e.memberId())) {
        outcomes.add(new Outcome(e, Result.FAILED, null));
      } else {
        candidates.add(e);
      }
    }

    List<Entry> taken = reserveSeats(scheduleId, candidates);
    for (Entry e : candidates) {
      if (!taken.contains(e)) {
        outcomes.add(new Outcome(e, Result.SOLD_OUT, null));
      }
    }
    if (taken.isEmpty()) {
      return outcomes;
    }

    LocalDateTime now = LocalDateTime.now();
    SqlParameterSource[] rows =
        taken.stream()
            .map(
                e ->
                    new MapSqlParameterSource()
                        .addValue("createdAt", Timestamp.valueOf(allocatedAt(e)))
                        .addValue("updatedAt", Timestamp.valueOf(now))
                        .addValue("number", e.reservationNumber())
                        .addValue("memberId", e.memberId())
                        .addValue("scheduleId", scheduleId)
                        .addValue("personCount", e.personCount())
                        .addValue("popupId", schedule.getPopupId())
                        .addValue("scheduleDate", schedule.getScheduleDate())
                        .addValue("startTime", schedule.getStartTime()))
            .toArray(SqlParameterSource[]::new);
    jdbc.batchUpdate(INSERT, rows);

    Map<String, Long> ids = idsByNumber(taken);
    for (Entry e : taken) {
      outcomes.add(new Outcome(e, Result.PERSISTED, ids.get(e.reservationNumber())));
    }
    return outcomes;
  }

  private List<Entry> reserveSeats(long scheduleId, List<Entry> candidates) {
    if (candidates.isEmpty()) {
      return List.of();
    }
    int sum = candidates.stream().mapToInt(Entry::personCount).sum();
    if (scheduleRepository.tryIncreaseCapacity(scheduleId, sum) == 1) {
      return candidates;
    }
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT max_capacity, now_capacity FROM schedules WHERE id = :id FOR UPDATE",
            Map.of("id", scheduleId));
    int room =
        ((Number) row.get("max_capacity")).intValue()
            - ((Number) row.get("now_capacity")).intValue();
    List<Entry> taken = new ArrayList<>();
    int used = 0;
    for (Entry e : candidates) {
      if (used + e.personCount() <= room) {
        taken.add(e);
        used += e.personCount();
      }
    }
    if (used > 0) {
      scheduleRepository.tryIncreaseCapacity(scheduleId, used);
    }
    return taken;
  }

  private static boolean isBookable(Schedule schedule) {
    try {
      schedule.validateBookable(LocalDateTime.now());
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static LocalDateTime allocatedAt(Entry e) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(e.allocatedAtMs()), ZoneId.systemDefault());
  }

  private Map<String, Long> idsByNumber(List<Entry> entries) {
    Map<String, Long> ids = new HashMap<>();
    jdbc.query(
        "SELECT reservation_number, id FROM reservations WHERE reservation_number IN (:numbers)",
        Map.of("numbers", entries.stream().map(Entry::reservationNumber).toList()),
        rs -> {
          ids.put(rs.getString(1), rs.getLong(2));
        });
    return ids;
  }

  private Set<Long> activeMembers(long scheduleId, List<Entry> entries) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT active_member_id FROM reservations WHERE schedule_id = :id AND active_member_id IN (:members)",
            Map.of("id", scheduleId, "members", entries.stream().map(Entry::memberId).toList()),
            Long.class));
  }

  private Set<Long> activeMemberIds(List<Entry> entries) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT id FROM members WHERE id IN (:members) AND status = 'ACTIVE'",
            Map.of("members", entries.stream().map(Entry::memberId).toList()),
            Long.class));
  }
}
