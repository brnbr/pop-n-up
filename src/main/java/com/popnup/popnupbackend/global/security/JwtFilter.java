package com.popnup.popnupbackend.global.security;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.enums.Role;
import com.popnup.popnupbackend.global.error.AuthErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

  private final JwtUtil jwtUtil;
  private final JwtBlacklistService jwtBlacklistService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String uri = request.getRequestURI();

    // 카카오페이 결제 승인 콜백은 JWT 인증 제외
    if (uri.equals("/api/v1/kakao-pay/approve")
        || uri.equals("/api/v1/kakao-pay/cancel")
        || uri.equals("/api/v1/kakao-pay/fail")) {
      filterChain.doFilter(request, response);
      return;
    }

    String authorizationHeader = request.getHeader("Authorization");

    // Bearer 토큰이 없는 요청의 허용 여부는 SecurityConfig가 판단한다.
    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    // Bearer 토큰의 Bearer를 제거
    String token = authorizationHeader.substring("Bearer ".length());

    try {
      if (jwtBlacklistService.isBlacklisted(token)) {
        sendUnauthorized(response, AuthErrorCode.BLACKLISTED_TOKEN);
        return;
      }
      authenticate(token, request);
    } catch (JwtException e) {
      sendUnauthorized(response, AuthErrorCode.INVALID_TOKEN);
      return;
    } catch (ServiceException e) {
      sendUnauthorized(response, (AuthErrorCode) e.getErrorCode());
      return;
    }

    filterChain.doFilter(request, response);
  }

  private void authenticate(String token, HttpServletRequest request) {
    Claims claims = jwtUtil.getClaims(token);
    Long userId;

    try {
      userId = Long.parseLong(claims.getSubject());
    } catch (NumberFormatException e) {
      throw AuthErrorCode.INVALID_USER_ID.toException();
    }

    String email = claims.get("email", String.class);
    String name = claims.get("name", String.class);

    if (email == null || email.isBlank()) {
      throw AuthErrorCode.INVALID_EMAIL.toException();
    }

    Role role;

    try {
      role = Role.of(claims.get("role", String.class));
    } catch (IllegalArgumentException e) {
      throw AuthErrorCode.INVALID_ROLE.toException();
    }

    JwtAuthenticationToken authentication =
        new JwtAuthenticationToken(
            new AuthUser(userId, email, name, role),
            List.of(new SimpleGrantedAuthority(role.getUserRole())));
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

    SecurityContext securityContext = SecurityContextHolder.getContext();
    securityContext.setAuthentication(authentication);
    SecurityContextHolder.setContext(securityContext);
  }

  private void sendUnauthorized(HttpServletResponse response, AuthErrorCode errorCode)
      throws IOException {

    response.setStatus(errorCode.getHttpStatus().value());
    response.setContentType("text/plain;charset=UTF-8");
    response.getWriter().write(errorCode.getMessage());
  }
}
