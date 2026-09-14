package com.popnup.popnupbackend.global.config;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.global.security.JwtUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

  private final JwtUtil jwtUtil;

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {

    CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();

    Member member = oAuth2User.getMember();

    String token =
        jwtUtil.createToken(member.getId(), member.getEmail(), member.getName(), member.getRole());

    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");

    response.getWriter().write("{\"token\":\"" + token + "\"}");
  }
}
