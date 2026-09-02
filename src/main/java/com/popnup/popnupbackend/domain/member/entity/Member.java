package com.popnup.popnupbackend.domain.member.entity;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.enums.MemberStatus;
import com.popnup.popnupbackend.domain.member.enums.Provider;
import com.popnup.popnupbackend.domain.member.enums.Role;
import com.popnup.popnupbackend.domain.member.exception.MemberNotProviderLocalException;
import com.popnup.popnupbackend.domain.member.exception.MemberNotValidateActiveException;
import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  // outh2는 비밀번호 null -> nullable = true..
  @Column(nullable = true)
  private String password;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private Role role;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private Provider provider;

  @Column(nullable = true)
  private String privateId;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private MemberStatus status;

  private Member(Long id) {
    this.id = id;
  }

  public static Member fromAuthUser(AuthUser authUser) {
    return new Member(authUser.getId());
  }

  public void updatePassword(String password) {
    this.password = password;
  }

  public static Member createLocal(String email, String password, String name) {
    Member member = new Member();
    member.email = email;
    member.password = password;
    member.name = name;
    member.provider = Provider.LOCAL;
    member.role = Role.ROLE_USER;
    member.status = MemberStatus.ACTIVE;

    return member;
  }

  // 활성화된 회원인지 확인하는 메서드
  public void validateActive() {
    if (this.status != MemberStatus.ACTIVE) {
      throw new MemberNotValidateActiveException();
    }
  }

  public void validateLocalProvider() {
    if (this.provider != Provider.LOCAL) {
      throw new MemberNotProviderLocalException();
    }
  }

  public void updateStatus(MemberStatus status) {
    this.status = status;
  }

  // 회원 상태 변경 메서드
  public void suspend() {
    this.status = MemberStatus.SUSPENDED;
  }

  public void activate() {
    this.status = MemberStatus.ACTIVE;
  }

  public void delete() {
    this.status = MemberStatus.DELETED;
  }
}
