package com.popnup.popnupbackend.global.config;

import com.popnup.popnupbackend.domain.member.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  private final SecretKey secretKey;

  public JwtUtil(@Value("${jwt.secret}") String secret) {
    System.out.println("========== JWT SECRET ==========");
    System.out.println(secret);
    System.out.println("================================");

    byte[] keyBytes = Decoders.BASE64.decode(secret);
    this.secretKey = Keys.hmacShaKeyFor(keyBytes);
  }

  public String createToken(Long userId, String email, String name, Role role) {
    Date now = new Date(); // iat 발급 시간
    Date expiration = new Date(now.getTime() + 60 * 60 * 1000); // exp 만료 시간

    return Jwts.builder()
        .subject(userId.toString())
        .claim("email", email)
        .claim("name", name)
        .claim("role", role.toString())
        .issuedAt(now)
        .expiration(expiration)
        .signWith(secretKey)
        .compact();
  }

  public Claims getClaims(String token) {
    return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
  }
}
