package com.popnup.popnupbackend.domain.reservation.queue;

import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Entry;
import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Outcome;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "reservation.queue.enabled", havingValue = "true")
public class QueueReservationWriter {

  static final String GROUP = "reservation-writer";
  private static final Duration CLAIM_IDLE = Duration.ofSeconds(30);

  private final StringRedisTemplate redis;
  private final QueueReservationPersister persister;
  private final ReservationQueueService queueService;
  private final ScheduleCapacityCache scheduleCapacityCache;
  private final MeterRegistry meterRegistry;
  private final int batchSize;
  private final String consumerName;
  private volatile boolean groupReady;

  public QueueReservationWriter(
      StringRedisTemplate redis,
      QueueReservationPersister persister,
      ReservationQueueService queueService,
      ScheduleCapacityCache scheduleCapacityCache,
      MeterRegistry meterRegistry,
      @Value("${reservation.queue.writer.batch-size:500}") int batchSize) {
    this.redis = redis;
    this.persister = persister;
    this.queueService = queueService;
    this.scheduleCapacityCache = scheduleCapacityCache;
    this.meterRegistry = meterRegistry;
    this.batchSize = Math.max(1, batchSize);
    this.consumerName = "writer-" + hostName();
  }

  @Scheduled(fixedDelayString = "${reservation.queue.writer.interval-ms:50}")
  public void drain() {
    try {
      if (!ensureGroup()) {
        return;
      }
      process(read(ReadOffset.from("0")));
      int handled;
      do {
        handled = process(read(ReadOffset.lastConsumed()));
      } while (handled >= batchSize);
    } catch (Exception e) {
      if (causedBy(e, "NOGROUP")) {
        groupReady = false;
      }
      long now = System.currentTimeMillis();
      if (now - lastWarnMs > 5_000) {
        lastWarnMs = now;
        log.warn("[QueueReservationWriter] 저장 처리 실패, 다음 주기에 다시 시도합니다: {}", e.toString());
      }
    }
  }

  private volatile long lastWarnMs;

  private static boolean causedBy(Throwable e, String code) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (String.valueOf(t.getMessage()).contains(code)) {
        return true;
      }
    }
    return false;
  }

  @Scheduled(fixedDelay = 10_000)
  public void claimStale() {
    if (!groupReady) {
      return;
    }
    try {
      PendingMessages pending =
          redis
              .opsForStream()
              .pending(ReservationQueueService.STREAM_KEY, GROUP, Range.unbounded(), batchSize);
      List<RecordId> stale = new ArrayList<>();
      for (PendingMessage m : pending) {
        if (!consumerName.equals(m.getConsumerName())
            && m.getElapsedTimeSinceLastDelivery().compareTo(CLAIM_IDLE) > 0) {
          stale.add(m.getId());
        }
      }
      if (!stale.isEmpty()) {
        redis
            .opsForStream()
            .claim(
                ReservationQueueService.STREAM_KEY,
                GROUP,
                consumerName,
                CLAIM_IDLE,
                stale.toArray(RecordId[]::new));
        log.warn("[QueueReservationWriter] 다른 작업자가 남긴 신청 {}건을 가져왔습니다", stale.size());
      }
    } catch (Exception e) {
      log.warn("[QueueReservationWriter] 남은 신청 가져오기 실패: {}", e.toString());
    }
  }

  private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
    List<MapRecord<String, Object, Object>> records =
        redis
            .opsForStream()
            .read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(ReservationQueueService.STREAM_KEY, offset));
    return records == null ? List.of() : records;
  }

  private int process(List<MapRecord<String, Object, Object>> records) {
    if (records.isEmpty()) {
      return 0;
    }
    List<Entry> entries =
        records.stream()
            .filter(r -> r.getValue() != null && !r.getValue().isEmpty())
            .map(QueueReservationWriter::toEntry)
            .toList();
    List<Outcome> outcomes = entries.isEmpty() ? List.of() : persister.persist(entries);
    long now = System.currentTimeMillis();
    for (Outcome o : outcomes) {
      Entry e = o.entry();
      switch (o.result()) {
        case PERSISTED ->
            queueService.recordResult(
                e.scheduleId(),
                e.memberId(),
                "A:" + e.reservationNumber() + ":" + o.reservationId());
        case DUPLICATE -> reject(e, "D");
        case FAILED -> reject(e, "F");
        case SOLD_OUT -> {
          queueService.recordResult(e.scheduleId(), e.memberId(), "S");
          meterRegistry.counter("reservation.queue.rejected", "result", "S").increment();
        }
      }
      meterRegistry
          .timer("reservation.queue.persist.lag")
          .record(Duration.ofMillis(Math.max(0, now - e.allocatedAtMs())));
    }
    RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
    redis.opsForStream().acknowledge(ReservationQueueService.STREAM_KEY, GROUP, ids);
    redis.opsForStream().delete(ReservationQueueService.STREAM_KEY, ids);
    return records.size();
  }

  private void reject(Entry e, String result) {
    queueService.recordResult(e.scheduleId(), e.memberId(), result);
    scheduleCapacityCache.releaseNow(e.scheduleId(), e.personCount());
    meterRegistry.counter("reservation.queue.rejected", "result", result).increment();
  }

  private static Entry toEntry(MapRecord<String, Object, Object> r) {
    Map<Object, Object> v = r.getValue();
    return new Entry(
        r.getId().getValue(),
        Long.parseLong((String) v.get("sid")),
        Long.parseLong((String) v.get("member")),
        Integer.parseInt((String) v.get("pc")),
        (String) v.get("no"),
        Long.parseLong((String) v.get("seq")),
        Long.parseLong((String) v.get("at")));
  }

  private boolean ensureGroup() {
    if (groupReady) {
      return true;
    }
    try {
      redis.execute(
          (RedisCallback<String>)
              (RedisConnection c) ->
                  c.streamCommands()
                      .xGroupCreate(
                          ReservationQueueService.STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                          GROUP,
                          ReadOffset.from("0"),
                          true));
    } catch (Exception e) {
      if (!causedBy(e, "BUSYGROUP")) {
        log.warn("[QueueReservationWriter] 소비자 그룹 준비 실패: {}", e.toString());
        return false;
      }
    }
    groupReady = true;
    return true;
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return UUID.randomUUID().toString();
    }
  }
}
