package com.popnup.popnupbackend.domain.payment.exception;

import com.popnup.popnupbackend.global.error.ErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PayErrorCode implements ErrorCode {
  PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제정보를 찾을 수 없습니다."),
  RESERVATION_NOT_MATCH(HttpStatus.FORBIDDEN, "예약자와 결제자가 일치하지 않습니다."),
  ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제가 완료된 결제입니다.");
  private final HttpStatus httpStatus;
  private final String message;

  public ServiceException toException() {
    return new ServiceException(this);
  }
}
