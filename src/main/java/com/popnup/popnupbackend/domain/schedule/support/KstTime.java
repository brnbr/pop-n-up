package com.popnup.popnupbackend.domain.schedule.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class KstTime {

  public static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private KstTime() {}

  public static LocalDateTime now() {
    return LocalDateTime.now(KST);
  }
}
