package com.popnup.popnupbackend.domain.admin.dto.request;

import com.popnup.popnupbackend.domain.member.enums.MemberStatus;
import com.popnup.popnupbackend.domain.member.enums.Provider;
import com.popnup.popnupbackend.domain.member.enums.Role;
import lombok.Getter;

@Getter
public class AdminMemberResponse {
  private final Long id;
  private final String email;
  private final String name;
  private final String role;
  private final String provider;
  private final String memberStatus;

  public AdminMemberResponse(
      Long id, String email, String name, Role role, Provider provider, MemberStatus memberStatus) {
    this.id = id;
    this.email = email;
    this.name = name;
    this.role = role.toString();
    this.provider = provider.toString();
    this.memberStatus = memberStatus.toString();
  }
}
