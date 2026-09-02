package com.popnup.popnupbackend.domain.popup.dto.request;

import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PopupCreateRequest {

  @NotBlank(message = "팝업스토어 제목은 필수입니다.")
  private String title;

  private String description;

  @NotNull(message = "카테고리는 필수입니다.")
  private PopupCategory category;

  @NotBlank(message = "지역 정보는 필수입니다.")
  private String region;

  @NotBlank(message = "상세 주소는 필수입니다.")
  private String address;

  private BigDecimal latitude;
  private BigDecimal longitude;

  @NotNull(message = "운영 시작일은 필수입니다.")
  private LocalDate startDate;

  @NotNull(message = "운영 종료일은 필수입니다.")
  private LocalDate endDate;

  private Boolean isFree = true;

  @PositiveOrZero(message = "가격은 0원 이상이어야 합니다.")
  private Integer price = 0;

  @NotNull(message = "팝업 상태는 필수입니다.")
  private PopupStatus status = PopupStatus.UPCOMING;

  @Valid private List<PopupImageRequest> images = new ArrayList<>();

  @Builder
  public PopupCreateRequest(
      String title,
      String description,
      PopupCategory category,
      String region,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      LocalDate startDate,
      LocalDate endDate,
      Boolean isFree,
      Integer price,
      PopupStatus status,
      List<PopupImageRequest> images) {
    this.title = title;
    this.description = description;
    this.category = category;
    this.region = region;
    this.address = address;
    this.latitude = latitude;
    this.longitude = longitude;
    this.startDate = startDate;
    this.endDate = endDate;
    this.isFree = isFree != null ? isFree : true;
    this.price = price != null ? price : 0;
    this.status = status != null ? status : PopupStatus.UPCOMING;
    this.images = images != null ? images : new ArrayList<>();
  }
}
