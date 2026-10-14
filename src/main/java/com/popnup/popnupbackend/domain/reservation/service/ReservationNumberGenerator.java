package com.popnup.popnupbackend.domain.reservation.service;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedGenerator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class ReservationNumberGenerator {

  private static final String PREFIX = "R";
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final int SUFFIX_LENGTH = 8;

  private static final TimeBasedGenerator UUID_GENERATOR = Generators.timeBasedGenerator();

  public String generate() {
    String today = LocalDate.now().format(DATE_FORMAT);
    String suffix =
        UUID_GENERATOR
            .generate()
            .toString()
            .replace("-", "")
            .substring(0, SUFFIX_LENGTH)
            .toUpperCase();
    return PREFIX + today + suffix;
  }
}
