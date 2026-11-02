package com.popnup.popnupbackend.domain.reservation.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class ReservationNumberGenerator {

  private static final String PREFIX = "R";
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final int SUFFIX_LENGTH = 10;

  private static final SecureRandom RANDOM = new SecureRandom();

  public String generate() {
    String today = LocalDate.now().format(DATE_FORMAT);

    byte[] randomBytes = new byte[SUFFIX_LENGTH];
    RANDOM.nextBytes(randomBytes);
    char[] suffix = new char[SUFFIX_LENGTH];
    for (int i = 0; i < SUFFIX_LENGTH; i++) {
      suffix[i] = ALPHABET[randomBytes[i] & (ALPHABET.length - 1)];
    }

    return PREFIX + today + new String(suffix);
  }
}
