package com.popnup.popnupbackend.domain.member.exception;

import com.popnup.popnupbackend.global.error.ServiceException;
import org.springframework.http.HttpStatus;

public class PasswordNotMatchException extends ServiceException {
  public PasswordNotMatchException() {
    super(HttpStatus.NOT_FOUND, "비밀번호가 올바르지 않습니다.");
  }
}
