package com.popnup.popnupbackend.domain.popup.repository;

import com.popnup.popnupbackend.domain.popup.entity.PopupImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PopupImageRepository extends JpaRepository<PopupImage, Long> {

    List<PopupImage> findByPopupIdOrderBySortOrderAsc(Long popupId);

    void deleteByPopupId(Long popupId);
}