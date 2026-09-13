package com.popnup.popnupbackend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 JWT입니다."),
  INVALID_USER_ID(HttpStatus.UNAUTHORIZED, "JWT의 사용자 정보가 올바르지 않습니다."),
  INVALID_EMAIL(HttpStatus.UNAUTHORIZED, "JWT의 이메일 정보가 올바르지 않습니다."),
  INVALID_ROLE(HttpStatus.UNAUTHORIZED, "JWT의 권한 정보가 올바르지 않습니다."),
  BLACKLISTED_TOKEN(HttpStatus.UNAUTHORIZED, "로그아웃된 JWT입니다");
  private final HttpStatus httpStatus;
  private final String message;

  public ServiceException toException() {
    return new ServiceException(this);
  }
}
