package com.popnup.popnupbackend.domain.admin.controller;

import com.popnup.popnupbackend.domain.admin.dto.request.AdminMemberResponse;
import com.popnup.popnupbackend.domain.admin.dto.request.AdminMemberStatusUpdateRequest;
import com.popnup.popnupbackend.domain.admin.sevice.AdminService;
import com.popnup.popnupbackend.global.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AdminMemberController {

  private final AdminService adminService;

  @GetMapping("/admin/members")
  public ResponseEntity<ApiResponse<List<AdminMemberResponse>>> getMembers() {
    return ResponseEntity.ok(ApiResponse.success(adminService.getMembers()));
  }

  @GetMapping("/admin/members/{memberId}")
  public ResponseEntity<ApiResponse<AdminMemberResponse>> getMember(@PathVariable Long memberId) {
    return ResponseEntity.ok(ApiResponse.success(adminService.getMember(memberId)));
  }

  @PatchMapping("/admin/members/{memberId}/status")
  public ResponseEntity<ApiResponse<Void>> updateMemberStatus(
      @PathVariable Long memberId, @Valid @RequestBody AdminMemberStatusUpdateRequest request) {
    adminService.updateMemberStatus(memberId, request);

    return ResponseEntity.ok(ApiResponse.success());
  }
}
