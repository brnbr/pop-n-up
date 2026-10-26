package com.popnup.popnupbackend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.auth.dto.request.SigninRequest;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.global.security.JwtBlacklistService;
import com.popnup.popnupbackend.global.security.JwtUtil;
import com.popnup.popnupbackend.global.security.LoginAttemptService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private MemberRepository memberRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @Mock private JwtUtil jwtUtil;

  @Mock private JwtBlacklistService jwtBlacklistService;

  @Mock private LoginAttemptService loginAttemptService;

  @InjectMocks private AuthService authService;

  private Member member;

  @BeforeEach
  void setUp() {
    member = Member.createLocal("test@test.com", "encodedPassword", "테스트회원");

    ReflectionTestUtils.setField(member, "id", 1L);
  }

  @Test
  void 로그아웃_성공() {
    // given
    String token = "test.jwt.token";
    long remainingMillis = 3_600_000L;

    when(jwtUtil.getRemainingExpirationMillis(token)).thenReturn(remainingMillis);

    // when
    authService.logout(token);

    // then
    verify(jwtUtil).getRemainingExpirationMillis(token);

    verify(jwtBlacklistService).blacklist(token, remainingMillis);
  }

  @Test
  void 존재하지_않는_이메일이면_동일한_로그인_실패_응답() {
    // given
    SigninRequest request = createSigninRequest("notfound@test.com", "password");

    when(loginAttemptService.isBlocked(request.getEmail(), "127.0.0.1")).thenReturn(false);

    when(memberRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> authService.signin(request, "127.0.0.1"))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");

    verify(loginAttemptService).recordFailure(request.getEmail(), "127.0.0.1");
  }

  @Test
  void 잘못된_비밀번호면_동일한_로그인_실패_응답() {
    // given
    SigninRequest request = createSigninRequest("test@test.com", "wrongPassword");

    when(loginAttemptService.isBlocked(request.getEmail(), "127.0.0.1")).thenReturn(false);

    when(memberRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(member));

    when(passwordEncoder.matches(request.getPassword(), member.getPassword())).thenReturn(false);

    // when & then
    assertThatThrownBy(() -> authService.signin(request, "127.0.0.1"))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");

    verify(loginAttemptService).recordFailure(request.getEmail(), "127.0.0.1");
  }

  @Test
  void 로그인_5회_실패하면_로그인이_차단된다() {
    // given
    SigninRequest request = createSigninRequest("test@test.com", "wrongPassword");

    when(loginAttemptService.isBlocked(request.getEmail(), "127.0.0.1")).thenReturn(true);

    // when & then
    assertThatThrownBy(() -> authService.signin(request, "127.0.0.1"))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");

    verify(memberRepository, never()).findByEmail(anyString());

    verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
  }

  @Test
  void 로그인_성공하면_실패_카운터가_삭제된다() {
    // given
    SigninRequest request = createSigninRequest("test@test.com", "correctPassword");

    when(loginAttemptService.isBlocked(request.getEmail(), "127.0.0.1")).thenReturn(false);

    when(memberRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(member));

    when(passwordEncoder.matches(request.getPassword(), member.getPassword())).thenReturn(true);

    when(jwtUtil.createToken(member.getId(), member.getEmail(), member.getName(), member.getRole()))
        .thenReturn("test.jwt.token");

    // when
    String token = authService.signin(request, "127.0.0.1");

    // then
    assertThat(token).isEqualTo("test.jwt.token");

    verify(loginAttemptService).clear(request.getEmail(), "127.0.0.1");
  }

  private SigninRequest createSigninRequest(String email, String password) {
    SigninRequest request = new SigninRequest();

    ReflectionTestUtils.setField(request, "email", email);
    ReflectionTestUtils.setField(request, "password", password);

    return request;
  }
}
