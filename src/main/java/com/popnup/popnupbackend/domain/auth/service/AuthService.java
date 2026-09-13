package com.popnup.popnupbackend.domain.auth.service;

import com.popnup.popnupbackend.domain.auth.dto.request.SigninRequest;
import com.popnup.popnupbackend.domain.auth.dto.request.SignupRequest;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.global.security.JwtBlacklistService;
import com.popnup.popnupbackend.global.security.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final MemberRepository memberRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;
  private final JwtBlacklistService jwtBlacklistService;

  @Transactional
  public void signup(SignupRequest request) {
    String password = request.getPassword();
    String encodedPassword = passwordEncoder.encode(password);

    Member member = Member.createLocal(request.getEmail(), encodedPassword, request.getName());
    memberRepository.save(member);
  }

  public String signin(@Valid SigninRequest request) {
    Member member =
        memberRepository
            .findByEmail(request.getEmail())
            .orElseThrow(MemberErrorCode.EMAIL_NOT_FOUND::toException);

    member.validateActive();

    String rawPassword = request.getPassword();
    String encodedPassword = member.getPassword();

    boolean matches = passwordEncoder.matches(rawPassword, encodedPassword);

    if (!matches) {
      throw MemberErrorCode.PASSWORD_NOT_MATCH.toException();
    }

    return jwtUtil.createToken(
        member.getId(), member.getEmail(), member.getName(), member.getRole());
  }

  public void logout(String token) {
    long remainingMills = jwtUtil.getRemainingExpirationMillis(token);

    jwtBlacklistService.blacklist(token, remainingMills);
  }
}
