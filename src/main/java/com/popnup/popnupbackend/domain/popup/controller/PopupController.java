package com.popnup.popnupbackend.domain.popup.controller;

import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupListResponse;
import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupResponse;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupCreateRequest;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupSearchCondition;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupUpdateRequest;
import com.popnup.popnupbackend.domain.popup.service.PopupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/popups")
@RequiredArgsConstructor
public class PopupController {

    private final PopupService popupService;

    @PostMapping
    public ResponseEntity<PopupResponse> createPopup(@RequestBody @Valid PopupCreateRequest request) {
        PopupResponse response = popupService.createPopup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PopupListResponse>> getPopupList(PopupSearchCondition condition) {
        List<PopupListResponse> popups = popupService.getPopupList(condition);
        return ResponseEntity.ok(popups);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PopupResponse> getPopupDetail(@PathVariable("id") Long id) {
        PopupResponse popup = popupService.getPopupDetail(id);
        return ResponseEntity.ok(popup);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PopupResponse> updatePopup(
            @PathVariable("id") Long id,
            @RequestBody @Valid PopupUpdateRequest request) {
        PopupResponse updatedPopup = popupService.updatePopup(id, request);
        return ResponseEntity.ok(updatedPopup);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePopup(@PathVariable("id") Long id) {
        popupService.deletePopup(id);
        return ResponseEntity.noContent().build();
    }
}