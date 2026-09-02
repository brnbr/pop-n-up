package com.popnup.popnupbackend.domain.auth.dto.request;

import com.popnup.popnupbackend.domain.member.enums.Role;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AuthUser {

  private final Long id;
  private final String email;
  private final String name;
  private final Role role;
}
