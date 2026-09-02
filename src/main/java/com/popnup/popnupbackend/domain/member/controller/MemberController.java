package com.popnup.popnupbackend.domain.member.controller;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.dto.reponse.MemberGetResponse;
import com.popnup.popnupbackend.domain.member.dto.request.MemberDeleteRequest;
import com.popnup.popnupbackend.domain.member.dto.request.MemberUpdatePasswordRequest;
import com.popnup.popnupbackend.domain.member.service.MemberService;
import com.popnup.popnupbackend.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MemberController {

  private final MemberService memberService;

  @GetMapping("/members/me")
  public ResponseEntity<ApiResponse<MemberGetResponse>> me(
      @AuthenticationPrincipal AuthUser authUser) {
    return ResponseEntity.ok(ApiResponse.success(memberService.getMe(authUser.getId())));
  }

  @PutMapping("/members/me/password")
  public ResponseEntity<ApiResponse<Void>> updatePassword(
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody MemberUpdatePasswordRequest request) {
    memberService.updatePassword(authUser, request);

    return ResponseEntity.ok(ApiResponse.success());
  }

  @DeleteMapping("/members/me")
  public ResponseEntity<ApiResponse<Void>> deleteMe(
      @AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody MemberDeleteRequest request) {
    memberService.deleteMe(authUser, request);

    return ResponseEntity.ok(ApiResponse.success());
  }
}
