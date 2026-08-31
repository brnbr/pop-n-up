package com.popnup.popnupbackend.domain.popup.service;

import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupListResponse;
import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupResponse;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupCreateRequest;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupImageRequest;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupSearchCondition;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupUpdateRequest;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupImage;
import com.popnup.popnupbackend.domain.popup.exception.PopupNotFoundException;
import com.popnup.popnupbackend.domain.popup.repository.PopupImageRepository;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PopupService {

  private final PopupRepository popupRepository;
  private final PopupImageRepository popupImageRepository;

  @Transactional
  public PopupResponse createPopup(PopupCreateRequest request) {
    Popup popup =
        Popup.builder()
            .title(request.getTitle())
            .description(request.getDescription())
            .category(request.getCategory())
            .region(request.getRegion())
            .address(request.getAddress())
            .latitude(request.getLatitude())
            .longitude(request.getLongitude())
            .startDate(request.getStartDate())
            .endDate(request.getEndDate())
            .isFree(request.getIsFree())
            .price(request.getPrice())
            .status(request.getStatus())
            .build();

    if (request.getImages() != null && !request.getImages().isEmpty()) {
      for (PopupImageRequest imgReq : request.getImages()) {
        PopupImage image =
            PopupImage.builder()
                .imageUrl(imgReq.getImageUrl())
                .imageType(imgReq.getImageType())
                .sortOrder(imgReq.getSortOrder())
                .build();
        popup.addImage(image);
      }
    }

    Popup savedPopup = popupRepository.save(popup);
    return PopupResponse.from(savedPopup);
  }

  @Transactional
  public PopupResponse getPopupDetail(Long popupId) {
    Popup popup =
        popupRepository.findById(popupId).orElseThrow(() -> new PopupNotFoundException(popupId));

    popup.increaseViewCount();

    return PopupResponse.from(popup);
  }

  public List<PopupListResponse> getPopupList(PopupSearchCondition condition) {
    List<Popup> popups;

    if (condition.getKeyword() != null && !condition.getKeyword().isBlank()) {
      popups = popupRepository.findByTitleContaining(condition.getKeyword().trim());
    } else if (condition.getRegion() != null && !condition.getRegion().isBlank()) {
      popups = popupRepository.findByRegionContaining(condition.getRegion().trim());
    } else if (condition.getCategory() != null) {
      popups = popupRepository.findByCategory(condition.getCategory());
    } else if (condition.getStatus() != null) {
      popups = popupRepository.findByStatus(condition.getStatus());
    } else {
      popups = popupRepository.findAll();
    }

    return popups.stream().map(PopupListResponse::from).collect(Collectors.toList());
  }

  @Transactional
  public PopupResponse updatePopup(Long popupId, PopupUpdateRequest request) {
    Popup popup =
        popupRepository.findById(popupId).orElseThrow(() -> new PopupNotFoundException(popupId));

    popup.update(
        request.getTitle(),
        request.getDescription(),
        request.getCategory(),
        request.getRegion(),
        request.getAddress(),
        request.getLatitude(),
        request.getLongitude(),
        request.getStartDate(),
        request.getEndDate(),
        request.getIsFree(),
        request.getPrice(),
        request.getStatus());

    if (request.getImages() != null) {
      popup.getImages().clear();
      for (PopupImageRequest imgReq : request.getImages()) {
        PopupImage image =
            PopupImage.builder()
                .imageUrl(imgReq.getImageUrl())
                .imageType(imgReq.getImageType())
                .sortOrder(imgReq.getSortOrder())
                .build();
        popup.addImage(image);
      }
    }

    return PopupResponse.from(popup);
  }

  @Transactional
  public void deletePopup(Long popupId) {
    Popup popup =
        popupRepository.findById(popupId).orElseThrow(() -> new PopupNotFoundException(popupId));

    popupRepository.delete(popup);
  }
}
