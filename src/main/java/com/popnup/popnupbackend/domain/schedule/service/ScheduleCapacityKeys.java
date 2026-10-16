package com.popnup.popnupbackend.domain.schedule.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

final class ScheduleCapacityKeys {

  private static final String USED_KEY_PREFIX = "schedule:capacity:used:";
  private static final String MAX_KEY_PREFIX = "schedule:capacity:max:";

  private ScheduleCapacityKeys() {}

  static String usedKey(Long scheduleId) {
    return USED_KEY_PREFIX + scheduleId;
  }

  static String maxKey(Long scheduleId) {
    return MAX_KEY_PREFIX + scheduleId;
  }

  static Duration ttlUntilEndOfDate(LocalDate scheduleDate) {
    LocalDateTime endOfDay = LocalDateTime.of(scheduleDate, LocalTime.MAX);
    Duration ttl = Duration.between(LocalDateTime.now(), endOfDay);
    return ttl.isNegative() ? Duration.ofMinutes(1) : ttl.plusHours(1);
  }
}
