package com.popnup.popnupbackend.domain.schedule.outbox;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@ConditionalOnExpression("${outbox.enabled:true} and '${outbox.mode:polling}' == 'cdc'")
public class OutboxCdcCleanup {

  private final OutboxEventRepository outboxEventRepository;
  private final Duration retention;

  public OutboxCdcCleanup(
      OutboxEventRepository outboxEventRepository,
      @Value("${outbox.relay.retention-hours:24}") long retentionHours) {
    this.outboxEventRepository = outboxEventRepository;
    this.retention = Duration.ofHours(retentionHours);
  }

  @Scheduled(fixedDelayString = "${outbox.cdc.cleanup-interval-ms:600000}")
  @Transactional
  public void cleanup() {
    long deleted = outboxEventRepository.deleteCreatedBefore(LocalDateTime.now().minus(retention));
    if (deleted > 0) {
      log.info("[OutboxCdc] 보관 기간이 지난 이벤트 {}건 삭제", deleted);
    }
  }
}
