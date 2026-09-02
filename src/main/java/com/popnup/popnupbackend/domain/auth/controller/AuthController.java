package com.popnup.popnupbackend.domain.auth.controller;

import com.popnup.popnupbackend.domain.auth.dto.request.SigninRequest;
import com.popnup.popnupbackend.domain.auth.dto.request.SignupRequest;
import com.popnup.popnupbackend.domain.auth.service.AuthService;
import com.popnup.popnupbackend.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/auth/signup")
  public ResponseEntity<ApiResponse<Void>> signup(@Valid @RequestBody SignupRequest request) {
    authService.signup(request);
    return ResponseEntity.ok(ApiResponse.success());
  }

  @PostMapping("/auth/signin")
  public ResponseEntity<ApiResponse<Void>> signin(@Valid @RequestBody SigninRequest request) {
    String jwt = authService.signin(request);
    return ResponseEntity.ok().header("Authorization", "Bearer " + jwt).body(ApiResponse.success());
  }
}
