package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationNumberGeneratorTest {

  private final ReservationNumberGenerator generator = new ReservationNumberGenerator();

  @Test
  @DisplayName("예약번호는 R + 오늘 날짜(yyyyMMdd) + 대문자 16진수 8자리 형식이다")
  void generate_format() {
    String number = generator.generate();

    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    assertThat(number).matches("R\\d{8}[0-9A-F]{8}");
    assertThat(number).startsWith("R" + today);
    assertThat(number).hasSize(17);
  }

  @Test
  @DisplayName("연속해서 만들어도 겹치지 않는다")
  void generate_unique() {
    Set<String> numbers = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      numbers.add(generator.generate());
    }

    assertThat(numbers).hasSize(10_000);
  }
}
