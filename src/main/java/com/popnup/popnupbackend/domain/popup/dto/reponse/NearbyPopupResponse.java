package com.popnup.popnupbackend.domain.popup.dto.reponse;

import com.popnup.popnupbackend.domain.popup.entity.ImageType;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import java.math.BigDecimal;
import java.time.LocalDate;

import com.popnup.popnupbackend.domain.popup.entity.PopupImage;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NearbyPopupResponse {

  private Long id;
  private String title;
  private PopupCategory category;
  private BigDecimal latitude;
  private BigDecimal longitude;
  private LocalDate startDate;
  private LocalDate endDate;
  private Double distance;
  private String thumbnailUrl;

  public static NearbyPopupResponse from(Popup popup, Double distance) {
      String thumbnailUrl =
              popup.getImages().stream()
                      .filter(image -> image.getImageType() == ImageType.THUMBNAIL)
                      .findFirst()
                      .map(PopupImage::getImageUrl)
                      .orElse(null);

      return NearbyPopupResponse.builder()
              .id(popup.getId())
              .title(popup.getTitle())
              .category(popup.getCategory())
              .latitude(popup.getLatitude())
              .longitude(popup.getLongitude())
              .startDate(popup.getStartDate())
              .endDate(popup.getEndDate())
              .thumbnailUrl(thumbnailUrl)
              .distance(distance)
              .build();
  }
}
