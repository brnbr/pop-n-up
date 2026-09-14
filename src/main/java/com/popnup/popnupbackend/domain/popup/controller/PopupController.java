package com.popnup.popnupbackend.domain.popup.controller;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupListResponse;
import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupResponse;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupCreateRequest;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupSearchCondition;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupUpdateRequest;
import com.popnup.popnupbackend.domain.popup.service.PopupService;
import com.popnup.popnupbackend.global.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PopupController {

  private final PopupService popupService;

  // 관리자 전용 CUD 로 변경 (/admin/popups)
  @PostMapping("/admin/popups")
  public ResponseEntity<ApiResponse<PopupResponse>> createPopup(
      @AuthenticationPrincipal AuthUser authUser, // 추가
      @RequestBody @Valid PopupCreateRequest request) {
    PopupResponse response = popupService.createPopup(authUser.getId(), request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success("팝업스토어가 성공적으로 등록되었습니다.", response));
  }

  @PutMapping("/admin/popups/{id}")
  public ResponseEntity<ApiResponse<PopupResponse>> updatePopup(
      @AuthenticationPrincipal AuthUser authUser, // 추가
      @PathVariable("id") Long id,
      @RequestBody @Valid PopupUpdateRequest request) {
    PopupResponse updatedPopup = popupService.updatePopup(id, authUser.getId(), request);
    return ResponseEntity.ok(ApiResponse.success("팝업스토어가 수정되었습니다.", updatedPopup));
  }

  @DeleteMapping("/admin/popups/{id}")
  public ResponseEntity<ApiResponse<Void>> deletePopup(
      @AuthenticationPrincipal AuthUser authUser, // 추가
      @PathVariable("id") Long id) {
    popupService.deletePopup(id, authUser.getId());
    return ResponseEntity.ok(ApiResponse.success("팝업스토어가 삭제되었습니다.", null));
  }

  // 일반 사용자 / 공개 API (/popups)

  @GetMapping("/popups")
  public ResponseEntity<ApiResponse<List<PopupListResponse>>> getPopupList(
      PopupSearchCondition condition) {
    List<PopupListResponse> popups = popupService.getPopupList(condition);
    return ResponseEntity.ok(ApiResponse.success("팝업 목록 조회 성공", popups));
  }

  @GetMapping("/popups/map")
  public ResponseEntity<ApiResponse<List<PopupResponse>>> getPopupsOnMap(
      @RequestParam("minLat") Double minLat,
      @RequestParam("maxLat") Double maxLat,
      @RequestParam("minLng") Double minLng,
      @RequestParam("maxLng") Double maxLng) {

    List<PopupResponse> result =
        popupService.getPopupsInBoundingBox(minLat, maxLat, minLng, maxLng);
    return ResponseEntity.ok(ApiResponse.success("지도 영역 팝업 조회 성공", result));
  }

  @GetMapping("/popups/nearby")
  public ResponseEntity<ApiResponse<List<PopupResponse>>> getNearbyPopups(
      @RequestParam("latitude") Double latitude,
      @RequestParam("longitude") Double longitude,
      @RequestParam(value = "radius", defaultValue = "3.0") Double radius) {

    List<PopupResponse> responses = popupService.getNearbyPopups(latitude, longitude, radius);
    return ResponseEntity.ok(ApiResponse.success("내 주변 팝업 조회 성공", responses));
  }

  @GetMapping("/popups/{id}")
  public ResponseEntity<ApiResponse<PopupResponse>> getPopupDetail(@PathVariable("id") Long id) {
    PopupResponse popup = popupService.getPopupDetail(id);
    return ResponseEntity.ok(ApiResponse.success("팝업 상세 조회 성공", popup));
  }
}
