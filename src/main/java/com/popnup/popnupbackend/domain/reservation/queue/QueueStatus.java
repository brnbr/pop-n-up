package com.popnup.popnupbackend.domain.reservation.queue;

public record QueueStatus(
    Long scheduleId,
    State state,
    long number,
    long ahead,
    int personCount,
    String reservationNumber,
    Long reservationId,
    boolean soldOut,
    long pollAfterMs) {

  public enum State {
    WAITING,
    ALLOCATED,
    SOLD_OUT,
    DUPLICATE,
    FAILED
  }

  public static QueueStatus waiting(
      Long scheduleId,
      long number,
      long ahead,
      int personCount,
      boolean soldOut,
      long pollAfterMs) {
    return new QueueStatus(
        scheduleId, State.WAITING, number, ahead, personCount, null, null, soldOut, pollAfterMs);
  }

  public static QueueStatus allocated(
      Long scheduleId, long number, int personCount, String reservationNumber, Long reservationId) {
    return new QueueStatus(
        scheduleId,
        State.ALLOCATED,
        number,
        0,
        personCount,
        reservationNumber,
        reservationId,
        false,
        0);
  }

  public static QueueStatus closed(Long scheduleId, State state, long number, int personCount) {
    return new QueueStatus(
        scheduleId, state, number, 0, personCount, null, null, state == State.SOLD_OUT, 0);
  }
}
