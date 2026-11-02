package com.popnup.popnupbackend.domain.reservation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "reservation.sort-key-backfill.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReservationSortKeyBackfill implements ApplicationRunner {

  private static final long STEP = 10_000;

  private static final String UPDATE_SQL =
      """
      UPDATE reservations r
         SET popup_id = (SELECT s.popup_id FROM schedules s WHERE s.id = r.schedule_id),
             schedule_date = (SELECT s.schedule_date FROM schedules s WHERE s.id = r.schedule_id),
             start_time = (SELECT s.start_time FROM schedules s WHERE s.id = r.schedule_id)
       WHERE r.id BETWEEN ? AND ? AND r.popup_id IS NULL
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public void run(ApplicationArguments args) {
    try {
      Long[] range =
          jdbcTemplate.queryForObject(
              "SELECT MIN(id), MAX(id) FROM reservations WHERE popup_id IS NULL",
              (rs, i) ->
                  new Long[] {(Long) rs.getObject(1, Long.class), rs.getObject(2, Long.class)});
      if (range == null || range[0] == null) {
        return;
      }
      long started = System.currentTimeMillis();
      long updated = 0;
      for (long from = range[0]; from <= range[1]; from += STEP) {
        updated += jdbcTemplate.update(UPDATE_SQL, from, from + STEP - 1);
      }
      log.info(
          "[ReservationSortKeyBackfill] 회차 키 채움 {}건, {}ms",
          updated,
          System.currentTimeMillis() - started);
    } catch (Exception e) {
      log.warn("[ReservationSortKeyBackfill] 회차 키 채우기 실패 (다음 기동 때 다시 시도): {}", e.toString());
    }
  }
}
