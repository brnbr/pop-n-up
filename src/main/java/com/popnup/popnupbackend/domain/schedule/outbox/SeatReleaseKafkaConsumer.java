package com.popnup.popnupbackend.domain.schedule.outbox;

import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${outbox.enabled:true} and '${outbox.mode:polling}' == 'cdc'")
public class SeatReleaseKafkaConsumer {

  private final ScheduleCapacityCache scheduleCapacityCache;
  private final MeterRegistry meterRegistry;

  @KafkaListener(
      topics = "${outbox.cdc.topic:outbox.SEAT_RELEASE}",
      groupId = "${outbox.cdc.group-id:seat-release}")
  public void onSeatRelease(ConsumerRecord<String, String> record) {
    long eventId = Long.parseLong(header(record, "id"));
    boolean applied =
        scheduleCapacityCache.applySeatRelease(eventId, SeatReleaseOutbox.decode(record.value()));
    meterRegistry
        .counter("outbox.relay", "outcome", applied ? OutboxRelay.APPLIED : OutboxRelay.DUPLICATE)
        .increment();
    String createdAt = header(record, "createdAt");
    if (createdAt != null) {
      meterRegistry
          .timer("outbox.delivery")
          .record(Duration.between(fromDebeziumMicros(createdAt), LocalDateTime.now()));
    }
  }

  static LocalDateTime fromDebeziumMicros(String micros) {
    long us = Long.parseLong(micros);
    return LocalDateTime.ofEpochSecond(
        Math.floorDiv(us, 1_000_000), (int) Math.floorMod(us, 1_000_000) * 1_000, ZoneOffset.UTC);
  }

  private static String header(ConsumerRecord<String, String> record, String key) {
    Header header = record.headers().lastHeader(key);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
