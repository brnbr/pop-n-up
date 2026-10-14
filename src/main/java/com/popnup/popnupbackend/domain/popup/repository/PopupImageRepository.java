package com.popnup.popnupbackend.domain.popup.repository;

import com.popnup.popnupbackend.domain.popup.entity.ImageType;
import com.popnup.popnupbackend.domain.popup.entity.PopupImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PopupImageRepository extends JpaRepository<PopupImage, Long> {

  List<PopupImage> findByPopupIdOrderBySortOrderAsc(Long popupId);

  void deleteByPopupId(Long popupId);

  @Query(
      """
    SELECT pi.popup.id AS popupId,
           pi.imageUrl AS imageUrl
    FROM PopupImage pi
    WHERE pi.popup.id IN :popupIds
      AND pi.imageType = :imageType
    ORDER BY pi.sortOrder ASC
    """)
  List<PopupThumbnailProjection> findThumbnails(
      @Param("popupIds") List<Long> popupIds, @Param("imageType") ImageType imageType);
}
