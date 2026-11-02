package com.popnup.popnupbackend.domain.reservation.exception;

import com.popnup.popnupbackend.global.error.ServiceException;
import lombok.Getter;

@Getter
public class BookingOverloadException extends ServiceException {

  private final int retryAfterSeconds;

  public BookingOverloadException(int retryAfterSeconds) {
    super(ReservationErrorCode.BOOKING_OVERLOADED);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
