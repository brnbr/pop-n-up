package com.popnup.popnupbackend.domain.popup.repository;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PopupRepository extends JpaRepository<Popup, Long> {

  List<Popup> findByRegionContaining(String region);

  List<Popup> findByCategory(PopupCategory category);

  List<Popup> findByStatus(PopupStatus status);

  List<Popup> findByTitleContaining(String keyword);

  // 위도 min~max, 경도 min~max 사이의 데이터 자동 검색
  @Query(
      """
    SELECT DISTINCT p
    FROM Popup p
    LEFT JOIN FETCH p.images
    WHERE p.latitude BETWEEN :minLat AND :maxLat
      AND p.longitude BETWEEN :minLng AND :maxLng
    """)
  List<Popup> findByLatitudeBetweenAndLongitudeBetween(
      @Param("minLat") Double minLat,
      @Param("maxLat") Double maxLat,
      @Param("minLng") Double minLng,
      @Param("maxLng") Double maxLng);
}
