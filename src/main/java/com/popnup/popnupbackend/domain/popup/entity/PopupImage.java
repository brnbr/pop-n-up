package com.popnup.popnupbackend.domain.popup.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "popup_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PopupImage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "popup_id", nullable = false)
  private Popup popup;

  @Column(name = "image_url", nullable = false, length = 500)
  private String imageUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "image_type", length = 30)
  private ImageType imageType;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  @Builder
  public PopupImage(Popup popup, String imageUrl, ImageType imageType, Integer sortOrder) {
    this.popup = popup;
    this.imageUrl = imageUrl;
    this.imageType = imageType;
    this.sortOrder = sortOrder != null ? sortOrder : 0;
  }

  public void assignPopup(Popup popup) {
    this.popup = popup;
  }
}
