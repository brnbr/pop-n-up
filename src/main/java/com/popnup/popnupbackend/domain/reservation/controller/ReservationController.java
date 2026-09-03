package com.popnup.popnupbackend.domain.reservation.controller;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import com.popnup.popnupbackend.global.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ReservationController {

  private final ReservationService reservationService;

  // 예약 생성
  @PostMapping("/reservations")
  public ResponseEntity<ApiResponse<ReservationCreateResponse>> createResrvation(
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody ReservationCreateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(reservationService.book(authUser.getId(), request)));
  }

  // 예약 취소
  @DeleteMapping("/reservations/{reservationId}")
  public ResponseEntity<ApiResponse<Void>> deleteReservation(
      @AuthenticationPrincipal AuthUser authUser, @PathVariable Long reservationId) {
    reservationService.cancel(authUser.getId(), reservationId);
    return ResponseEntity.ok(ApiResponse.success());
  }

  // 전체 조회
  @GetMapping("/reservations")
  public ResponseEntity<ApiResponse<List<ReservationResponse>>> getAll(
      @AuthenticationPrincipal AuthUser authUser) {
    List<ReservationResponse> response = reservationService.allReservations(authUser.getId());
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  // 단 건 조회
  @GetMapping("/reservations/{reservationId}")
  public ResponseEntity<ApiResponse<ReservationResponse>> getOne(
      @AuthenticationPrincipal AuthUser authUser, @Valid @PathVariable Long reservationId) {
    return ResponseEntity.ok(
        ApiResponse.success(reservationService.oneReservation(authUser.getId(), reservationId)));
  }
}
