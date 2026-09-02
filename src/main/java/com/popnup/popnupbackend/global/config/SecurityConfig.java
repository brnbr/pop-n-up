package com.popnup.popnupbackend.global.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
@EnableWebSecurity // Spring Security의 웹 요청 보안 설정을 활성화
@EnableMethodSecurity(securedEnabled = true) // 메서드에 선언한 @Secured 권한 검사를 활성화
public class SecurityConfig {

  private final JwtFilter jwtFilter;

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(
            AbstractHttpConfigurer::disable) // 쿠키가 아닌 Authorization 헤더로 JWT를 전달하므로 CSRF 보호를 사용하지 않음
        .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())) // frame 옵션 허용 (h2)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(AbstractHttpConfigurer::disable) // 인증 전 요청을 세션에 저장한 뒤 복원하는 리다이렉트 흐름을 사용하지 않음
        .formLogin(AbstractHttpConfigurer::disable) // 서버가 로그인 HTML 폼을 제공하는 방식을 사용하지 않음
        .httpBasic(AbstractHttpConfigurer::disable) // 브라우저의 HTTP Basic 인증 팝업 방식을 사용하지 않음
        .logout(AbstractHttpConfigurer::disable) // 서버 세션을 무효화하는 로그아웃 방식이 아닌 JWT 폐기 방식을 사용함
        .rememberMe(AbstractHttpConfigurer::disable) // 자동 로그인을 위한 Remember-Me 쿠키를 발급하지 않음
        .exceptionHandling(
            exception ->
                exception
                    .authenticationEntryPoint(
                        (request, response, cause) ->
                            sendError(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "Authorization 헤더에 JWT가 필요합니다."))
                    .accessDeniedHandler(
                        (request, response, cause) ->
                            sendError(response, HttpServletResponse.SC_FORBIDDEN, "접근 권한이 없습니다.")))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/h2-console/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/signin")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/signup")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/members")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/gatherings")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/admin")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtFilter, AnonymousAuthenticationFilter.class) // JwtFilter 등록
        .build();
  }

  /** JwtFilter는 Spring Security 필터 체인에서만 실행해야 한다. 서블릿 컨테이너가 같은 필터를 별도로 자동 등록하는 것을 막는다. */
  @Bean
  public FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtFilter filter) {
    FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  private void sendError(HttpServletResponse response, int status, String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("text/plain;charset=UTF-8");
    response.getWriter().write(message);
  }
}
