package com.popnup.popnupbackend.domain.member.enums;

import com.popnup.popnupbackend.domain.member.exception.RoleNotMatchException;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {
  ROLE_USER(Authority.USER),
  ROLE_ADMIN(Authority.ADMIN);

  private final String userRole;

  public static Role of(String role) {
    return Arrays.stream(Role.values())
        .filter(value -> value.name().equalsIgnoreCase(role))
        .findFirst()
        .orElseThrow(() -> new RoleNotMatchException());
  }

  public static class Authority {
    public static final String USER = "ROLE_USER";
    public static final String ADMIN = "ROLE_ADMIN";
  }
}
