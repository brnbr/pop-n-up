package com.popnup.popnupbackend.domain.member.exception;

import com.popnup.popnupbackend.global.error.ServiceException;
import org.springframework.http.HttpStatus;

public class MemberNotFoundException extends ServiceException {
  public MemberNotFoundException() {
    super(HttpStatus.NOT_FOUND, "member을 찾을 수 없습니다.");
  }
}
