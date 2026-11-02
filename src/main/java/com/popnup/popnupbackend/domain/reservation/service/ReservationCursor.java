package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.AdminCursor;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.MemberCursor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;

final class ReservationCursor {

  private static final String ADMIN = "a1";
  private static final String MEMBER = "m1";

  private ReservationCursor() {}

  static String encode(AdminCursor c) {
    return wrap(ADMIN + "|" + c.scheduleDate() + "|" + c.startTime() + "|" + c.id());
  }

  static String encode(MemberCursor c) {
    return wrap(MEMBER + "|" + c.createdAt() + "|" + c.id());
  }

  static AdminCursor decodeAdmin(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    String[] p = unwrap(cursor, ADMIN, 4);
    try {
      return new AdminCursor(LocalDate.parse(p[1]), LocalTime.parse(p[2]), Long.parseLong(p[3]));
    } catch (RuntimeException e) {
      throw ReservationErrorCode.INVALID_CURSOR.toException();
    }
  }

  static MemberCursor decodeMember(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    String[] p = unwrap(cursor, MEMBER, 3);
    try {
      return new MemberCursor(LocalDateTime.parse(p[1]), Long.parseLong(p[2]));
    } catch (RuntimeException e) {
      throw ReservationErrorCode.INVALID_CURSOR.toException();
    }
  }

  private static String wrap(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static String[] unwrap(String cursor, String prefix, int parts) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] p = raw.split("\\|");
      if (p.length != parts || !prefix.equals(p[0])) {
        throw ReservationErrorCode.INVALID_CURSOR.toException();
      }
      return p;
    } catch (IllegalArgumentException e) {
      throw ReservationErrorCode.INVALID_CURSOR.toException();
    }
  }
}
