package com.popnup.popnupbackend.domain.popup.dto.reponse;

import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class PopupResponse {

    private final Long id;
    private final String title;
    private final String description;
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
    private final List<PopupImageResponse> images;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    @Builder
    public PopupResponse(Long id, String title, String description, PopupCategory category, String region,
                         String address, BigDecimal latitude, BigDecimal longitude, LocalDate startDate,
                         LocalDate endDate, Boolean isFree, Integer price, PopupStatus status,
                         Long viewCount, List<PopupImageResponse> images, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.title = title;
        this.description = description;
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
        this.images = images;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PopupResponse from(Popup popup) {
        return PopupResponse.builder()
                .id(popup.getId())
                .title(popup.getTitle())
                .description(popup.getDescription())
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
                .images(popup.getImages() != null ?
                        popup.getImages().stream()
                                .map(PopupImageResponse::from)
                                .collect(Collectors.toList()) : List.of())
                .createdAt(popup.getCreatedAt())
                .updatedAt(popup.getUpdatedAt())
                .build();
    }
}