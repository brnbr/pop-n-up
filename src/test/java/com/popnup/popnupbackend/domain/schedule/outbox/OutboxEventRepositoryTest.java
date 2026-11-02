package com.popnup.popnupbackend.domain.schedule.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.global.config.JpaConfig;
import com.popnup.popnupbackend.global.config.QuerydslConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({QuerydslConfig.class, JpaConfig.class})
class OutboxEventRepositoryTest {

  @Autowired private OutboxEventRepository repository;
  @Autowired private TestEntityManager em;

  private OutboxEvent save(LocalDateTime createdAt) {
    return repository.save(OutboxEvent.of(OutboxEvent.SEAT_RELEASE, "1:1", createdAt));
  }

  @Test
  @DisplayName("미처리 이벤트만 오래된 순으로 가져오고, 처리됨을 남기면 미처리 수에서 빠진다")
  void claim_mark_count() {
    LocalDateTime now = LocalDateTime.now();
    OutboxEvent a = save(now);
    OutboxEvent b = save(now);
    OutboxEvent c = save(now);
    em.flush();

    assertThat(repository.claimUnpublished(2))
        .extracting(OutboxEvent::getId)
        .containsExactly(a.getId(), b.getId());

    assertThat(repository.markPublished(List.of(a.getId()), "APPLIED", now)).isEqualTo(1);
    em.clear();

    assertThat(repository.countUnpublished()).isEqualTo(2);
    assertThat(repository.claimUnpublished(10))
        .extracting(OutboxEvent::getId)
        .containsExactly(b.getId(), c.getId());
    assertThat(repository.findById(a.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
  }

  @Test
  @DisplayName("회차 1,000개에 걸친 청크 만료 이벤트(약 9KB, tinytext 한도 255바이트의 35배)도 저장된다")
  void largeChunkPayload_saved() {
    java.util.Map<Long, Integer> seats = new java.util.HashMap<>();
    for (long id = 100_001; id <= 101_000; id++) {
      seats.put(id, 3);
    }
    String payload = SeatReleaseOutbox.encode(seats);

    OutboxEvent saved =
        repository.saveAndFlush(
            OutboxEvent.of(OutboxEvent.SEAT_RELEASE, payload, LocalDateTime.now()));
    em.clear();

    assertThat(payload.length()).isGreaterThan(8_000);
    assertThat(repository.findById(saved.getId()).orElseThrow().getPayload()).isEqualTo(payload);
  }

  @Test
  @DisplayName("이미 처리된 이벤트는 다시 처리됨으로 덮어쓰지 않고, 보관 기간이 지난 처리 이벤트만 지운다")
  void markOnce_deleteOld() {
    LocalDateTime now = LocalDateTime.now();
    OutboxEvent old = save(now.minusDays(2));
    OutboxEvent recent = save(now);
    OutboxEvent pending = save(now);
    repository.markPublished(List.of(old.getId()), "APPLIED", now.minusDays(2));
    repository.markPublished(List.of(recent.getId()), "APPLIED", now);
    em.clear();

    assertThat(repository.markPublished(List.of(old.getId()), "DUPLICATE", now)).isZero();
    assertThat(repository.deletePublishedBefore(now.minusDays(1))).isEqualTo(1);
    em.clear();

    assertThat(repository.findById(old.getId())).isEmpty();
    assertThat(repository.findById(recent.getId())).isPresent();
    assertThat(repository.findById(pending.getId())).isPresent();
  }
}
