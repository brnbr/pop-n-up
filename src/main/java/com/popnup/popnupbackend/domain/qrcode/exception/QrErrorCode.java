package com.popnup.popnupbackend.domain.qrcode.exception;

import com.popnup.popnupbackend.global.error.ErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum QrErrorCode implements ErrorCode {
  QR_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "QR 코드 생성에 실패 했습니다.");

  private final HttpStatus httpStatus;
  private final String message;

  public ServiceException toException() {
    return new ServiceException(this);
  }
}
