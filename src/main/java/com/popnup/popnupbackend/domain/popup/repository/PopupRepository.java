package com.popnup.popnupbackend.domain.popup.repository;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PopupRepository extends JpaRepository<Popup, Long> {

  List<Popup> findByRegionContaining(String region);

  List<Popup> findByCategory(PopupCategory category);

  List<Popup> findByStatus(PopupStatus status);

  List<Popup> findByTitleContaining(String keyword);
}
