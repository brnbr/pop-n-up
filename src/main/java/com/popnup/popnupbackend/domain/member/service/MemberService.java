package com.popnup.popnupbackend.domain.member.service;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.dto.request.MemberDeleteRequest;
import com.popnup.popnupbackend.domain.member.dto.request.MemberUpdatePasswordRequest;
import com.popnup.popnupbackend.domain.member.dto.response.MemberGetResponse;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.enums.MemberStatus;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {
  private final MemberRepository memberRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional(readOnly = true)
  public com.popnup.popnupbackend.domain.member.dto.response.MemberGetResponse getMe(Long id) {

    Member member =
        memberRepository
            .findByIdAndStatusNot(id, MemberStatus.DELETED)
            .orElseThrow(MemberErrorCode.MEMBER_NOT_FOUND::toException);

    return new MemberGetResponse(
        member.getId(), member.getEmail(), member.getName(), member.getRole());
  }

  @Transactional
  public void updatePassword(AuthUser authUser, MemberUpdatePasswordRequest request) {
    Member member =
        memberRepository
            .findById(authUser.getId())
            .orElseThrow(MemberErrorCode.MEMBER_NOT_FOUND::toException);

    member.validateActive();
    member.validateLocalProvider();

    String oldEncodedPassword = member.getPassword();
    String oldRawPassword = request.getOldPassword();

    boolean matches = passwordEncoder.matches(oldRawPassword, oldEncodedPassword);
    if (!matches) {
      throw MemberErrorCode.PASSWORD_NOT_MATCH.toException();
    }

    String encodedNewPassword = passwordEncoder.encode(request.getNewPassword());
    member.updatePassword(encodedNewPassword);
  }

  @Transactional
  public void deleteMe(AuthUser authUser, MemberDeleteRequest request) {
    Member member =
        memberRepository
            .findById(authUser.getId())
            .orElseThrow(MemberErrorCode.MEMBER_NOT_FOUND::toException);

    member.validateActive();

    String rawPassword = request.getPassword();
    String encodedPassword = member.getPassword();
    boolean matches = passwordEncoder.matches(rawPassword, encodedPassword);

    if (!matches) {
      throw MemberErrorCode.PASSWORD_NOT_MATCH.toException();
    }

    member.delete();
  }
}
