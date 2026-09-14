package com.popnup.popnupbackend.domain.auth.service;

import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.global.security.JwtBlacklistService;
import com.popnup.popnupbackend.global.security.JwtUtil;
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
}
