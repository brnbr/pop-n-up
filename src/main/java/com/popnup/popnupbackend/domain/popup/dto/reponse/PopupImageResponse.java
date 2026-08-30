package com.popnup.popnupbackend.domain.popup.dto.reponse;

import com.popnup.popnupbackend.domain.popup.entity.ImageType;
import com.popnup.popnupbackend.domain.popup.entity.PopupImage;
import lombok.Builder;
import lombok.Getter;

@Getter
public class PopupImageResponse {

    private final Long id;
    private final String imageUrl;
    private final ImageType imageType;
    private final Integer sortOrder;

    @Builder
    public PopupImageResponse(Long id, String imageUrl, ImageType imageType, Integer sortOrder) {
        this.id = id;
        this.imageUrl = imageUrl;
        this.imageType = imageType;
        this.sortOrder = sortOrder;
    }

    public static PopupImageResponse from(PopupImage popupImage) {
        return PopupImageResponse.builder()
                .id(popupImage.getId())
                .imageUrl(popupImage.getImageUrl())
                .imageType(popupImage.getImageType())
                .sortOrder(popupImage.getSortOrder())
                .build();
    }
}