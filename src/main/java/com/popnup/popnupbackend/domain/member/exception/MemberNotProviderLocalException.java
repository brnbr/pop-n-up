package com.popnup.popnupbackend.domain.member.exception;

import com.popnup.popnupbackend.global.error.ServiceException;
import org.springframework.http.HttpStatus;

public class MemberNotProviderLocalException extends ServiceException {
  public MemberNotProviderLocalException() {
    super(HttpStatus.BAD_REQUEST, "해당 회원은 LOCAL회원이 아닙니다.");
  }
}
