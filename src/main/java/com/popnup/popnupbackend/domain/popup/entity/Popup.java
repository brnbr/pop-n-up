package com.popnup.popnupbackend.domain.popup.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "popup")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Popup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PopupCategory category;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "is_free", nullable = false)
    private Boolean isFree = true;

    @Column(nullable = false)
    private Integer price = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PopupStatus status;

    @Column(name = "view_count", nullable = false)
    private Long viewCount = 0L;

    @OneToMany(mappedBy = "popup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PopupImage> images = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Builder
    public Popup(String title, String description, PopupCategory category, String region,
                 String address, BigDecimal latitude, BigDecimal longitude, LocalDate startDate,
                 LocalDate endDate, Boolean isFree, Integer price, PopupStatus status) {
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
        this.viewCount = 0L;
    }

    public void increaseViewCount() {
        this.viewCount++;
    }

    public void update(String title, String description, PopupCategory category, String region,
                       String address, BigDecimal latitude, BigDecimal longitude, LocalDate startDate,
                       LocalDate endDate, Boolean isFree, Integer price, PopupStatus status) {
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
    }

    public void addImage(PopupImage image) {
        this.images.add(image);
        image.assignPopup(this);
    }
}