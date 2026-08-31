package com.popnup.popnupbackend.domain.popup.repository;

import com.popnup.popnupbackend.domain.popup.entity.PopupImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PopupImageRepository extends JpaRepository<PopupImage, Long> {

  List<PopupImage> findByPopupIdOrderBySortOrderAsc(Long popupId);

  void deleteByPopupId(Long popupId);
}
