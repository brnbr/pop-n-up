package com.popnup.popnupbackend.domain.reservation.queue;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationQueueController {

  private final ReservationQueueService queueService;

  @PostMapping("/reservations/queue/{scheduleId}")
  public ResponseEntity<ApiResponse<QueueStatus>> join(
      @AuthenticationPrincipal AuthUser authUser,
      @PathVariable Long scheduleId,
      @Valid @RequestBody(required = false) QueueJoinRequest request) {
    int personCount =
        request == null || request.getPersonCount() == null ? 1 : request.getPersonCount();
    return ResponseEntity.ok(
        ApiResponse.success(queueService.join(scheduleId, authUser.getId(), personCount)));
  }

  @GetMapping("/reservations/queue/{scheduleId}")
  public ResponseEntity<ApiResponse<QueueStatus>> status(
      @AuthenticationPrincipal AuthUser authUser, @PathVariable Long scheduleId) {
    return ResponseEntity.ok(
        ApiResponse.success(queueService.status(scheduleId, authUser.getId())));
  }

  @Secured("ROLE_ADMIN")
  @PutMapping("/admin/schedules/{scheduleId}/queue")
  public ResponseEntity<ApiResponse<Void>> setQueue(
      @PathVariable Long scheduleId, @RequestParam boolean enabled) {
    queueService.setEnabled(scheduleId, enabled);
    return ResponseEntity.ok(ApiResponse.success());
  }
}
