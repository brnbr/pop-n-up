package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationNumberGeneratorTest {

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final ReservationNumberGenerator generator = new ReservationNumberGenerator();

  @Test
  @DisplayName("예약번호는 R + 오늘 날짜(yyyyMMdd) + 혼동되는 문자(I, L, O, U)를 뺀 영숫자 10자리 형식이다")
  void generate_format() {
    LocalDate before = LocalDate.now();
    String number = generator.generate();
    LocalDate after = LocalDate.now();

    assertThat(number).matches("R\\d{8}[0-9A-HJKMNP-TV-Z]{10}");
    assertThat(number).hasSize(19);
    assertThat(number.substring(1, 9)).isIn(before.format(DATE_FORMAT), after.format(DATE_FORMAT));
  }

  @Test
  @DisplayName("연속해서 만들어도 겹치지 않는다")
  void generate_unique() {
    Set<String> numbers = ConcurrentHashMap.newKeySet();
    for (int i = 0; i < 100_000; i++) {
      numbers.add(generator.generate());
    }

    assertThat(numbers).hasSize(100_000);
  }

  @Test
  @DisplayName("여러 스레드가 동시에 만들어도 겹치지 않는다")
  void generate_uniqueUnderConcurrency() throws Exception {
    int threads = 12;
    int perThread = 20_000;
    Set<String> numbers = ConcurrentHashMap.newKeySet();
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);

    for (int t = 0; t < threads; t++) {
      executor.submit(
          () -> {
            ready.countDown();
            go.await();
            for (int i = 0; i < perThread; i++) {
              numbers.add(generator.generate());
            }
            return null;
          });
    }
    ready.await();
    go.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    assertThat(numbers).hasSize(threads * perThread);
  }

  @Test
  @DisplayName("무작위 부분은 시각에 묶여 반복되지 않고 문자가 고르게 쓰인다")
  void generate_suffixUsesWholeAlphabet() {
    Set<Character> used = ConcurrentHashMap.newKeySet();
    for (int i = 0; i < 2_000; i++) {
      for (char c : generator.generate().substring(9).toCharArray()) {
        used.add(c);
      }
    }

    assertThat(used).hasSize(32);
  }
}
