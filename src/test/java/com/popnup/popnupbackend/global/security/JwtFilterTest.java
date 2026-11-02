package com.popnup.popnupbackend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.enums.Role;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtFilterTest {

  // 테스트 전용 HS256 키 (base64, 64바이트)
  private static final String SECRET =
      "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtZmlsdGVyLXRlc3QtMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYg==";

  private final JwtUtil jwtUtil = new JwtUtil(SECRET);
  private JwtBlacklistService blacklistService;
  private JwtFilter filter;

  @BeforeEach
  void setUp() {
    blacklistService = mock(JwtBlacklistService.class);
    filter = new JwtFilter(jwtUtil, blacklistService);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletRequest requestWithToken() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/reservations/my");
    request.addHeader(
        "Authorization",
        "Bearer " + jwtUtil.createToken(7L, "user@test.com", "회원", Role.ROLE_USER));
    return request;
  }

  @Test
  @DisplayName("블랙리스트 조회가 Redis 시간 초과로 실패해도 401을 내지 않고 서명 검증만으로 인증한다")
  void blacklistLookupFails_authenticatesWithoutBlacklist() throws Exception {
    given(blacklistService.isBlacklisted(anyString()))
        .willThrow(new QueryTimeoutException("Redis command timed out"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(requestWithToken(), response, chain);

    verify(chain).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(200);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(((AuthUser) authentication.getPrincipal()).getId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("블랙리스트에 있는 토큰은 그대로 401로 거절한다 (조회가 성공한 경우)")
  void blacklistedToken_rejected() throws Exception {
    given(blacklistService.isBlacklisted(anyString())).willReturn(true);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(requestWithToken(), response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
