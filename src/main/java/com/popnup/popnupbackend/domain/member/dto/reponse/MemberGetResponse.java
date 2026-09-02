package com.popnup.popnupbackend.domain.member.dto.reponse;

import com.popnup.popnupbackend.domain.member.enums.Role;
import lombok.Getter;

@Getter
public class MemberGetResponse {

  private final Long id;
  private final String email;
  private final String name;
  private final String role;

  public MemberGetResponse(Long id, String email, String name, Role role) {
    this.id = id;
    this.email = email;
    this.name = name;
    this.role = role.toString();
  }
}
