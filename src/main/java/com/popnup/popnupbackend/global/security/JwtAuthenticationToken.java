package com.popnup.popnupbackend.global.security;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

  private final AuthUser principal;

  public JwtAuthenticationToken(
      AuthUser principal, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.principal = principal;
    super.setAuthenticated(true);
  }

  @Override
  public AuthUser getPrincipal() {
    return principal;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return principal.getId().toString();
  }

  @Override
  public void setAuthenticated(boolean authenticated) {
    if (authenticated) {
      throw new IllegalArgumentException("인증 완료 상태는 생성자로만 설정할 수 있습니다.");
    }
    super.setAuthenticated(false);
  }
}
