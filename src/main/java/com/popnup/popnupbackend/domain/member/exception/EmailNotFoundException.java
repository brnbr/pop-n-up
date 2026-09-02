package com.popnup.popnupbackend.domain.member.exception;

import com.popnup.popnupbackend.global.error.ServiceException;
import org.springframework.http.HttpStatus;

public class EmailNotFoundException extends ServiceException {

  public EmailNotFoundException() {
    super(HttpStatus.NOT_FOUND, "해당 이메일이 없습니다.");
  }
}
