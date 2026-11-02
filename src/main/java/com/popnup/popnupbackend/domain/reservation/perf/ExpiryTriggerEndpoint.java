package com.popnup.popnupbackend.domain.reservation.perf;

import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor;
import com.popnup.popnupbackend.domain.reservation.service.ReservationTimeoutProcessor.ExpiryMode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("loadtest")
@Endpoint(id = "perfexpire")
@RequiredArgsConstructor
public class ExpiryTriggerEndpoint {

  private final ReservationTimeoutProcessor reservationTimeoutProcessor;
  private final AtomicBoolean running = new AtomicBoolean();

  @WriteOperation
  public Map<String, Object> expire(ExpiryMode mode, int chunkSize, int workers) {
    if (!running.compareAndSet(false, true)) {
      return Map.of("status", "busy");
    }
    try {
      return Map.of(
          "status",
          "done",
          "result",
          reservationTimeoutProcessor.expireUnpaidReservations(mode, chunkSize, workers));
    } finally {
      running.set(false);
    }
  }
}
