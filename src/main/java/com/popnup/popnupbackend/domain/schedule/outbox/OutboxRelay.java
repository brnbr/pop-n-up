package com.popnup.popnupbackend.domain.schedule.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@ConditionalOnExpression("${outbox.enabled:true} and '${outbox.mode:polling}' == 'polling'")
public class OutboxRelay {

  static final String APPLIED = "APPLIED";
  static final String DUPLICATE = "DUPLICATE";

  private final OutboxEventRepository outboxEventRepository;
  private final SeatReleaseOutbox seatReleaseOutbox;
  private final TransactionTemplate transaction;
  private final MeterRegistry meterRegistry;
  private final int batchSize;
  private final Duration retention;
  private final AtomicLong pending = new AtomicLong();
  private LocalDateTime lastCleanup = LocalDateTime.MIN;

  public OutboxRelay(
      OutboxEventRepository outboxEventRepository,
      SeatReleaseOutbox seatReleaseOutbox,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry,
      @Value("${outbox.relay.batch-size:100}") int batchSize,
      @Value("${outbox.relay.retention-hours:24}") long retentionHours) {
    this.outboxEventRepository = outboxEventRepository;
    this.seatReleaseOutbox = seatReleaseOutbox;
    this.transaction = new TransactionTemplate(transactionManager);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.meterRegistry = meterRegistry;
    this.batchSize = Math.max(1, batchSize);
    this.retention = Duration.ofHours(retentionHours);
    meterRegistry.gauge("outbox.pending", pending);
  }

  @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:200}")
  public void relay() {
    int handled;
    int total = 0;
    do {
      handled = relayBatch();
      total += Math.max(handled, 0);
    } while (handled == batchSize);
    pending.set(handled == 0 && total == 0 ? 0 : outboxEventRepository.countUnpublished());
    cleanup();
  }

  int relayBatch() {
    Integer handled =
        transaction.execute(
            status -> {
              List<OutboxEvent> events = outboxEventRepository.claimUnpublished(batchSize);
              List<Long> applied = new ArrayList<>();
              List<Long> duplicate = new ArrayList<>();
              boolean failed = false;
              for (OutboxEvent event : events) {
                try {
                  (seatReleaseOutbox.apply(event) ? applied : duplicate).add(event.getId());
                  meterRegistry
                      .timer("outbox.delivery")
                      .record(Duration.between(event.getCreatedAt(), LocalDateTime.now()));
                } catch (Exception e) {
                  meterRegistry.counter("outbox.relay.failure").increment();
                  log.warn("[OutboxRelay] Redis 반영 실패, 다음 주기에 다시 시도합니다: {}", e.getMessage());
                  failed = true;
                  break;
                }
              }
              LocalDateTime at = LocalDateTime.now();
              mark(applied, APPLIED, at);
              mark(duplicate, DUPLICATE, at);
              return failed ? -1 : events.size();
            });
    return handled == null ? 0 : handled;
  }

  private void mark(List<Long> ids, String outcome, LocalDateTime at) {
    if (ids.isEmpty()) {
      return;
    }
    outboxEventRepository.markPublished(ids, outcome, at);
    meterRegistry.counter("outbox.relay", "outcome", outcome).increment(ids.size());
  }

  private void cleanup() {
    LocalDateTime now = LocalDateTime.now();
    if (lastCleanup.plusMinutes(10).isAfter(now)) {
      return;
    }
    lastCleanup = now;
    long deleted =
        transaction.execute(
            status -> outboxEventRepository.deletePublishedBefore(now.minus(retention)));
    if (deleted > 0) {
      log.info("[OutboxRelay] 보관 기간이 지난 이벤트 {}건 삭제", deleted);
    }
  }
}
