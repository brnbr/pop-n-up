package com.popnup.popnupbackend.domain.member.exception;

import com.popnup.popnupbackend.global.error.ErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {
  EMAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 이메일을 찾을 수 없습니다."),
  MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 멤버를 찾을 수 없습니다."),
  MEMBER_NOT_PROVIDER_LOCAL(HttpStatus.BAD_REQUEST, "해당 회원은 LOCAL 회원이 아닙니다."),
  MEMBER_NOT_VALIDATE_ACTIVE(HttpStatus.BAD_REQUEST, "활성 상태의 회원이 아닙니다."),
  PASSWORD_NOT_MATCH(HttpStatus.BAD_REQUEST, "비밀번호가 올바르지 않습니다."),
  EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
  ROLE_NOT_MATCH(HttpStatus.FORBIDDEN, "해당 ROLE은 유효하지 않습니다.");

  private final HttpStatus httpStatus;
  private final String message;

  public ServiceException toException() {
    return new ServiceException(this);
  }
}
