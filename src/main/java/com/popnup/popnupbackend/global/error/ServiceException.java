package com.popnup.popnupbackend.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ServiceException extends RuntimeException {

  private final ErrorCode errorCode;
  private final HttpStatus httpStatus;

  // errorCode
  public ServiceException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
    this.httpStatus = errorCode.getHttpStatus();
  }

  // 커스텀
  public ServiceException(String message) {
    super(message);
    this.errorCode = null;
    this.httpStatus = HttpStatus.BAD_REQUEST;
  }

  // 커스텀
  public ServiceException(HttpStatus httpStatus, String message) {
    super(message);
    this.errorCode = null;
    this.httpStatus = httpStatus;
  }
}
