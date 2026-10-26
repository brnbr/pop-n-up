package com.popnup.popnupbackend.domain.auth.service;

import com.popnup.popnupbackend.domain.auth.dto.request.SigninRequest;
import com.popnup.popnupbackend.domain.auth.dto.request.SignupRequest;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.global.security.JwtBlacklistService;
import com.popnup.popnupbackend.global.security.JwtUtil;
import com.popnup.popnupbackend.global.security.LoginAttemptService;
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
  private final LoginAttemptService loginAttemptService;

  @Transactional
  public void signup(SignupRequest request) {

    if (memberRepository.existsByEmail(request.getEmail())) {
      throw MemberErrorCode.EMAIL_ALREADY_EXISTS.toException();
    }

    String password = request.getPassword();
    String encodedPassword = passwordEncoder.encode(password);

    Member member = Member.createLocal(request.getEmail(), encodedPassword, request.getName());
    memberRepository.save(member);
  }

  public String signin(SigninRequest request, String clientIp) {

    String email = request.getEmail();

    if (loginAttemptService.isBlocked(email, clientIp)) {
      throw MemberErrorCode.INVALID_CREDENTIALS.toException();
    }

    Member member = memberRepository.findByEmail(email).orElse(null);

    if (member == null) {
      loginAttemptService.recordFailure(email, clientIp);
      throw MemberErrorCode.INVALID_CREDENTIALS.toException();
    }

    member.validateActive();

    boolean matches = passwordEncoder.matches(request.getPassword(), member.getPassword());

    if (!matches) {
      loginAttemptService.recordFailure(email, clientIp);
      throw MemberErrorCode.INVALID_CREDENTIALS.toException();
    }

    loginAttemptService.clear(email, clientIp);

    return jwtUtil.createToken(
        member.getId(), member.getEmail(), member.getName(), member.getRole());
  }

  public void logout(String token) {
    long remainingMills = jwtUtil.getRemainingExpirationMillis(token);

    jwtBlacklistService.blacklist(token, remainingMills);
  }
}
