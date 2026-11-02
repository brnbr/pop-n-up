package com.popnup.popnupbackend.domain.reservation.queue;

import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.service.ReservationNumberGenerator;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore.Restore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReservationQueueService {

  private static final String ENABLED_KEY = "queue:enabled";
  static final String STREAM_KEY = "reservation:queue:stream";

  private static final String STATUS_SCRIPT =
      """
      local v = redis.call('HGET', KEYS[1], ARGV[1])
      local decided = '0'
      if ARGV[4] == '1' then
        if not v then
          local s = redis.call('INCR', KEYS[2])
          v = s .. ':' .. ARGV[2] .. ':' .. ARGV[5]
          redis.call('HSET', KEYS[1], ARGV[1], v)
          local mx = redis.call('GET', KEYS[6])
          if mx and redis.call('ZCARD', KEYS[3]) == 0 then
            local pc = tonumber(ARGV[5])
            if tonumber(redis.call('GET', KEYS[7]) or '0') + pc <= tonumber(mx) then
              redis.call('INCRBY', KEYS[7], pc)
              redis.call('XADD', KEYS[8], 'MAXLEN', '~', '1000000', '*',
                'sid', ARGV[7], 'member', ARGV[1], 'pc', pc, 'no', ARGV[6], 'seq', s, 'at', ARGV[2])
              redis.call('HSET', KEYS[4], ARGV[1], 'A:' .. ARGV[6])
            else
              redis.call('HSET', KEYS[4], ARGV[1], 'S')
            end
            redis.call('SET', KEYS[5], s)
            decided = '1'
          else
            redis.call('ZADD', KEYS[3], s, ARGV[1])
          end
        end
        for i = 1, 5 do redis.call('EXPIRE', KEYS[i], ARGV[3]) end
      elseif not v then
        return 'NONE'
      end
      local r = redis.call('HGET', KEYS[4], ARGV[1])
      if r then return 'RESULT|' .. v .. '|' .. r .. '|' .. decided end
      local pc = tonumber(string.match(v, ':(%d+)$'))
      local mx = redis.call('GET', KEYS[6])
      local used = redis.call('GET', KEYS[7])
      local soldOut = '0'
      if mx and used and tonumber(used) + pc > tonumber(mx) then soldOut = '1' end
      return 'WAITING|' .. v .. '|' .. (redis.call('GET', KEYS[5]) or '0') .. '|' .. soldOut
      """;

  private static final String ALLOCATE_SCRIPT =
      """
      local mx = redis.call('GET', KEYS[5])
      if not mx then return {-1, 0} end
      mx = tonumber(mx)
      local used = tonumber(redis.call('GET', KEYS[6]) or '0')
      local n = tonumber(ARGV[2])
      if used >= mx then n = redis.call('ZCARD', KEYS[1]) end
      if n < 1 then return {0, 0} end
      local popped = redis.call('ZPOPMIN', KEYS[1], n)
      local allocated, soldOut, last = 0, 0, nil
      for i = 1, #popped, 2 do
        local m, s = popped[i], popped[i + 1]
        local v = redis.call('HGET', KEYS[2], m)
        local pc = tonumber(string.match(v or ':1', ':(%d+)$'))
        if used + pc <= mx and ARGV[5 + allocated] then
          used = redis.call('INCRBY', KEYS[6], pc)
          local no = ARGV[5 + allocated]
          allocated = allocated + 1
          redis.call('XADD', KEYS[7], 'MAXLEN', '~', '1000000', '*',
            'sid', ARGV[1], 'member', m, 'pc', pc, 'no', no, 'seq', s, 'at', ARGV[4])
          redis.call('HSET', KEYS[3], m, 'A:' .. no)
        else
          redis.call('HSET', KEYS[3], m, 'S')
          soldOut = soldOut + 1
        end
        last = s
      end
      if last then redis.call('SET', KEYS[4], last) end
      for _, k in ipairs({KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[8]}) do redis.call('EXPIRE', k, ARGV[3]) end
      return {allocated, soldOut}
      """;

  private static final long MIN_POLL_MS = 200;
  private static final long MAX_POLL_MS = 15_000;

  private final boolean featureEnabled;
  private final long allocatePerSecond;
  private final String idleTtlSeconds;
  private final ObjectProvider<RedissonClient> redissonProvider;
  private final ReservationNumberGenerator numberGenerator;
  private final ScheduleRepository scheduleRepository;
  private final ScheduleCapacityCounterStore counterStore;
  private final MeterRegistry meterRegistry;

  private volatile Set<String> enabledCache = Set.of();
  private volatile long enabledCachedAt;

  public ReservationQueueService(
      @Value("${reservation.queue.enabled:false}") boolean featureEnabled,
      @Value("${reservation.queue.admit-per-second:200}") long allocatePerSecond,
      @Value("${reservation.queue.idle-ttl-seconds:86400}") long idleTtlSeconds,
      ObjectProvider<RedissonClient> redissonProvider,
      ReservationNumberGenerator numberGenerator,
      ScheduleRepository scheduleRepository,
      ScheduleCapacityCounterStore counterStore,
      MeterRegistry meterRegistry) {
    this.featureEnabled = featureEnabled;
    this.allocatePerSecond = Math.max(1, allocatePerSecond);
    this.idleTtlSeconds = String.valueOf(Math.max(1, idleTtlSeconds));
    this.redissonProvider = redissonProvider;
    this.numberGenerator = numberGenerator;
    this.scheduleRepository = scheduleRepository;
    this.counterStore = counterStore;
    this.meterRegistry = meterRegistry;
  }

  public boolean isFeatureEnabled() {
    return featureEnabled;
  }

  public void setEnabled(Long scheduleId, boolean on) {
    requireFeature();
    RSet<String> set = redisson().getSet(ENABLED_KEY);
    if (on) {
      set.add(String.valueOf(scheduleId));
    } else {
      set.remove(String.valueOf(scheduleId));
    }
    enabledCachedAt = 0;
  }

  public boolean isQueued(Long scheduleId) {
    if (!featureEnabled) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (now - enabledCachedAt > 1_000) {
      try {
        enabledCache = redisson().<String>getSet(ENABLED_KEY).readAll();
      } catch (Exception e) {
        log.warn("[ReservationQueue] 대기열 회차 목록 조회 실패, 직전 목록을 씁니다: {}", e.toString());
      }
      enabledCachedAt = now;
    }
    return enabledCache.contains(String.valueOf(scheduleId));
  }

  public QueueStatus join(Long scheduleId, Long memberId, int personCount) {
    requireQueued(scheduleId);
    if (personCount < 1 || personCount > 10) {
      throw ReservationErrorCode.INVALID_PERSON_COUNT.toException();
    }
    QueueStatus status = evalStatus(scheduleId, memberId, true, personCount);
    meterRegistry.counter("reservation.queue.join").increment();
    return status;
  }

  public QueueStatus status(Long scheduleId, Long memberId) {
    requireQueued(scheduleId);
    return evalStatus(scheduleId, memberId, false, 1);
  }

  private QueueStatus evalStatus(Long scheduleId, Long memberId, boolean join, int personCount) {
    List<Object> keys = new ArrayList<>(stateKeys(scheduleId));
    keys.addAll(ScheduleCapacityCache.capacityKeys(scheduleId));
    keys.add(STREAM_KEY);
    String result =
        script()
            .eval(
                RScript.Mode.READ_WRITE,
                STATUS_SCRIPT,
                RScript.ReturnType.VALUE,
                keys,
                String.valueOf(memberId),
                String.valueOf(System.currentTimeMillis()),
                idleTtlSeconds,
                join ? "1" : "0",
                String.valueOf(personCount),
                join ? numberGenerator.generate() : "",
                String.valueOf(scheduleId));
    String[] f = result.split("\\|");
    if ("NONE".equals(f[0])) {
      throw ReservationErrorCode.QUEUE_NOT_JOINED.toException();
    }
    String[] entry = f[1].split(":");
    long seq = Long.parseLong(entry[0]);
    int pc = Integer.parseInt(entry[2]);
    if ("WAITING".equals(f[0])) {
      long ahead = Math.max(0, seq - Long.parseLong(f[2]) - 1);
      return QueueStatus.waiting(scheduleId, seq, ahead, pc, "1".equals(f[3]), pollAfterMs(ahead));
    }
    if ("1".equals(f[3])) {
      meterRegistry
          .counter(
              f[2].startsWith("A:") ? "reservation.queue.allocated" : "reservation.queue.sold_out")
          .increment();
    }
    return resultStatus(scheduleId, seq, pc, f[2]);
  }

  private static QueueStatus resultStatus(Long scheduleId, long seq, int pc, String r) {
    if (r.startsWith("A:")) {
      String[] a = r.split(":");
      return QueueStatus.allocated(
          scheduleId, seq, pc, a[1], a.length > 2 ? Long.valueOf(a[2]) : null);
    }
    QueueStatus.State state =
        switch (r) {
          case "S" -> QueueStatus.State.SOLD_OUT;
          case "D" -> QueueStatus.State.DUPLICATE;
          default -> QueueStatus.State.FAILED;
        };
    return QueueStatus.closed(scheduleId, state, seq, pc);
  }

  long pollAfterMs(long ahead) {
    long untilTurn = (ahead + 1) * 1_000 / allocatePerSecond;
    return Math.min(MAX_POLL_MS, Math.max(MIN_POLL_MS, untilTurn));
  }

  public void rejectDirectBooking(Long scheduleId) {
    if (isQueued(scheduleId)) {
      meterRegistry.counter("reservation.queue.entry.rejected").increment();
      throw ReservationErrorCode.QUEUE_ENTRY_REQUIRED.toException();
    }
  }

  void recordResult(Long scheduleId, Long memberId, String value) {
    redisson()
        .<String, String>getMap(resultKey(scheduleId))
        .fastPut(String.valueOf(memberId), value);
  }

  public void allocate(int perTick) {
    if (!featureEnabled) {
      return;
    }
    for (String id : redisson().<String>getSet(ENABLED_KEY).readAll()) {
      Long scheduleId = Long.valueOf(id);
      List<Object> keys =
          new ArrayList<>(
              List.of(
                  waitingKey(scheduleId),
                  membersKey(scheduleId),
                  resultKey(scheduleId),
                  doneKey(scheduleId)));
      keys.addAll(ScheduleCapacityCache.capacityKeys(scheduleId));
      keys.add(STREAM_KEY);
      keys.add(seqKey(scheduleId));
      List<Object> args = new ArrayList<>(4 + perTick);
      args.add(id);
      args.add(String.valueOf(perTick));
      args.add(idleTtlSeconds);
      args.add(String.valueOf(System.currentTimeMillis()));
      for (int i = 0; i < perTick; i++) {
        args.add(numberGenerator.generate());
      }
      List<Object> r =
          script()
              .eval(
                  RScript.Mode.READ_WRITE,
                  ALLOCATE_SCRIPT,
                  RScript.ReturnType.LIST,
                  keys,
                  args.toArray());
      long allocated = Long.parseLong(String.valueOf(r.get(0)));
      long soldOut = Long.parseLong(String.valueOf(r.get(1)));
      if (allocated < 0) {
        restoreCapacity(scheduleId);
        continue;
      }
      if (allocated > 0) {
        meterRegistry.counter("reservation.queue.allocated").increment(allocated);
      }
      if (soldOut > 0) {
        meterRegistry.counter("reservation.queue.sold_out").increment(soldOut);
      }
    }
  }

  private void restoreCapacity(Long scheduleId) {
    scheduleRepository
        .findById(scheduleId)
        .ifPresent(
            s ->
                counterStore.restoreAll(
                    List.of(
                        new Restore(
                            s.getId(),
                            s.getScheduleDate(),
                            s.getMaxCapacity(),
                            s.getNowCapacity()))));
    log.warn("[ReservationQueue] 회차 정원 키가 없어 DB 값으로 채웠습니다. scheduleId={}", scheduleId);
  }

  private void requireFeature() {
    if (!featureEnabled) {
      throw ReservationErrorCode.QUEUE_DISABLED.toException();
    }
  }

  private void requireQueued(Long scheduleId) {
    requireFeature();
    if (!isQueued(scheduleId)) {
      throw ReservationErrorCode.QUEUE_DISABLED.toException();
    }
  }

  private RedissonClient redisson() {
    return redissonProvider.getObject();
  }

  private RScript script() {
    return redisson().getScript();
  }

  private static List<Object> stateKeys(Long scheduleId) {
    return List.of(
        membersKey(scheduleId),
        seqKey(scheduleId),
        waitingKey(scheduleId),
        resultKey(scheduleId),
        doneKey(scheduleId));
  }

  static String membersKey(Long scheduleId) {
    return "queue:" + scheduleId + ":members";
  }

  static String seqKey(Long scheduleId) {
    return "queue:" + scheduleId + ":seq";
  }

  static String waitingKey(Long scheduleId) {
    return "queue:" + scheduleId + ":waiting";
  }

  static String resultKey(Long scheduleId) {
    return "queue:" + scheduleId + ":result";
  }

  static String doneKey(Long scheduleId) {
    return "queue:" + scheduleId + ":done";
  }
}
