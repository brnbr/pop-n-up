package com.popnup.popnupbackend.domain.popup.repository;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PopupRepository extends JpaRepository<Popup, Long> {

    List<Popup> findByRegionContaining(String region);

    List<Popup> findByCategory(PopupCategory category);

    List<Popup> findByStatus(PopupStatus status);

    List<Popup> findByTitleContaining(String keyword);
}