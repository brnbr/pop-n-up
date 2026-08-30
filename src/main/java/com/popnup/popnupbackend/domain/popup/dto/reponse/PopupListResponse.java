package com.popnup.popnupbackend.domain.popup.dto.reponse;

import com.popnup.popnupbackend.domain.popup.entity.*;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
public class PopupListResponse {

    private final Long id;
    private final String title;
    private final PopupCategory category;
    private final String region;
    private final String address;
    private final BigDecimal latitude;
    private final BigDecimal longitude;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Boolean isFree;
    private final Integer price;
    private final PopupStatus status;
    private final Long viewCount;
    private final String thumbnailUrl;

    @Builder
    public PopupListResponse(Long id, String title, PopupCategory category, String region, String address,
                             BigDecimal latitude, BigDecimal longitude, LocalDate startDate, LocalDate endDate,
                             Boolean isFree, Integer price, PopupStatus status, Long viewCount, String thumbnailUrl) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.region = region;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.startDate = startDate;
        this.endDate = endDate;
        this.isFree = isFree;
        this.price = price;
        this.status = status;
        this.viewCount = viewCount;
        this.thumbnailUrl = thumbnailUrl;
    }

    public static PopupListResponse from(Popup popup) {
        String thumbnail = popup.getImages() != null && !popup.getImages().isEmpty()
                ? popup.getImages().stream()
                .filter(img -> img.getImageType() == ImageType.THUMBNAIL)
                .map(PopupImage::getImageUrl)
                .findFirst()
                .orElse(popup.getImages().get(0).getImageUrl())
                : null;

        return PopupListResponse.builder()
                .id(popup.getId())
                .title(popup.getTitle())
                .category(popup.getCategory())
                .region(popup.getRegion())
                .address(popup.getAddress())
                .latitude(popup.getLatitude())
                .longitude(popup.getLongitude())
                .startDate(popup.getStartDate())
                .endDate(popup.getEndDate())
                .isFree(popup.getIsFree())
                .price(popup.getPrice())
                .status(popup.getStatus())
                .viewCount(popup.getViewCount())
                .thumbnailUrl(thumbnail)
                .build();
    }
}