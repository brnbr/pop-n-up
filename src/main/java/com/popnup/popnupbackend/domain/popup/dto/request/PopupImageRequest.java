package com.popnup.popnupbackend.domain.popup.dto.request;

import com.popnup.popnupbackend.domain.popup.entity.ImageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PopupImageRequest {

    @NotBlank(message = "이미지 URL은 필수입니다.")
    private String imageUrl;

    @NotNull(message = "이미지 유형은 필수입니다.")
    private ImageType imageType;

    private Integer sortOrder = 0;

    @Builder
    public PopupImageRequest(String imageUrl, ImageType imageType, Integer sortOrder) {
        this.imageUrl = imageUrl;
        this.imageType = imageType;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
    }
}