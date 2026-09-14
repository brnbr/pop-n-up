package com.popnup.popnupbackend.domain.popup.service;

import com.popnup.popnupbackend.domain.popup.dto.naver.NaverGeocodeResponse;
import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupListResponse;
import com.popnup.popnupbackend.domain.popup.dto.reponse.PopupResponse;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupCreateRequest;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupImageRequest;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupSearchCondition;
import com.popnup.popnupbackend.domain.popup.dto.request.PopupUpdateRequest;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupImage;
import com.popnup.popnupbackend.domain.popup.exception.PopupNotFoundException;
import com.popnup.popnupbackend.domain.popup.repository.PopupImageRepository;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PopupService {

  private final PopupRepository popupRepository;
  private final PopupImageRepository popupImageRepository;
  private final NaverGeocodeService naverGeocodeService;

  @Transactional
  public PopupResponse createPopup(Long memberId, PopupCreateRequest request) {

    BigDecimal latitude = request.getLatitude();
    BigDecimal longitude = request.getLongitude();

    if (request.getAddress() != null && !request.getAddress().isBlank()) {
      NaverGeocodeResponse.AddressItem coordinate =
          naverGeocodeService.getCoordinatesByAddress(request.getAddress());
      if (coordinate != null) {
        latitude = coordinate.getLatitude();
        longitude = coordinate.getLongitude();
      }
    }

    Popup popup =
        Popup.builder()
            .title(request.getTitle())
            .description(request.getDescription())
            .category(request.getCategory())
            .region(request.getRegion())
            .address(request.getAddress())
            .latitude(latitude)
            .longitude(longitude)
            .startDate(request.getStartDate())
            .endDate(request.getEndDate())
            .isFree(request.getIsFree())
            .price(request.getPrice())
            .status(request.getStatus())
            .build();

    if (request.getImages() != null && !request.getImages().isEmpty()) {
      for (PopupImageRequest imgReq : request.getImages()) {
        PopupImage image =
            PopupImage.builder()
                .imageUrl(imgReq.getImageUrl())
                .imageType(imgReq.getImageType())
                .sortOrder(imgReq.getSortOrder())
                .build();
        popup.addImage(image);
      }
    }

    Popup savedPopup = popupRepository.save(popup);
    return PopupResponse.from(savedPopup);
  }

  @Transactional
  public PopupResponse getPopupDetail(Long popupId) {
    Popup popup =
        popupRepository.findById(popupId).orElseThrow(() -> new PopupNotFoundException(popupId));

    popup.increaseViewCount();

    return PopupResponse.from(popup);
  }

  public List<PopupListResponse> getPopupList(PopupSearchCondition condition) {
    List<Popup> popups;

    if (condition.getKeyword() != null && !condition.getKeyword().isBlank()) {
      popups = popupRepository.findByTitleContaining(condition.getKeyword().trim());
    } else if (condition.getRegion() != null && !condition.getRegion().isBlank()) {
      popups = popupRepository.findByRegionContaining(condition.getRegion().trim());
    } else if (condition.getCategory() != null) {
      popups = popupRepository.findByCategory(condition.getCategory());
    } else if (condition.getStatus() != null) {
      popups = popupRepository.findByStatus(condition.getStatus());
    } else {
      popups = popupRepository.findAll();
    }

    return popups.stream().map(PopupListResponse::from).collect(Collectors.toList());
  }

  @Transactional
  public PopupResponse updatePopup(Long popupId, Long memberId, PopupUpdateRequest request) {
    Popup popup =
        popupRepository.findById(popupId).orElseThrow(() -> new PopupNotFoundException(popupId));

    BigDecimal latitude = request.getLatitude();
    BigDecimal longitude = request.getLongitude();

    if (request.getAddress() != null && !request.getAddress().isBlank()) {
      NaverGeocodeResponse.AddressItem coordinate =
          naverGeocodeService.getCoordinatesByAddress(request.getAddress());
      if (coordinate != null) {
        latitude = coordinate.getLatitude();
        longitude = coordinate.getLongitude();
      }
    }

    popup.update(
        request.getTitle(),
        request.getDescription(),
        request.getCategory(),
        request.getRegion(),
        request.getAddress(),
        latitude,
        longitude,
        request.getStartDate(),
        request.getEndDate(),
        request.getIsFree(),
        request.getPrice(),
        request.getStatus());

    if (request.getImages() != null) {
      popup.getImages().clear();
      for (PopupImageRequest imgReq : request.getImages()) {
        PopupImage image =
            PopupImage.builder()
                .imageUrl(imgReq.getImageUrl())
                .imageType(imgReq.getImageType())
                .sortOrder(imgReq.getSortOrder())
                .build();
        popup.addImage(image);
      }
    }

    return PopupResponse.from(popup);
  }

  @Transactional
  public void deletePopup(Long popupId, Long memberId) {
    Popup popup =
        popupRepository.findById(popupId).orElseThrow(() -> new PopupNotFoundException(popupId));

    popupRepository.delete(popup);
  }

  @Transactional(readOnly = true)
  public List<PopupResponse> getPopupsInBoundingBox(
      Double minLat, Double maxLat, Double minLng, Double maxLng) {

    // 유효성 검사: 작은 값이 먼저 와야 함
    if (minLat > maxLat || minLng > maxLng) {
      throw new IllegalArgumentException("시작 좌표는 끝 좌표보다 작아야 합니다.");
    }

    return popupRepository
        .findByLatitudeBetweenAndLongitudeBetween(minLat, maxLat, minLng, maxLng)
        .stream()
        .map(PopupResponse::from)
        .toList();
  }

  // 내 위치 기준 반경 N km 이내 팝업스토어 거리순 조회
  @Transactional(readOnly = true)
  public List<PopupResponse> getNearbyPopups(Double latitude, Double longitude, Double radius) {
    // 1. 기본 반경 방어 (null이거나 0 이하일 경우 기본 3.0km)
    double searchRadius = (radius != null && radius > 0) ? radius : 3.0;

    // 2. 위도 1도 ≈ 111km, 경도 1도 ≈ 111km * cos(위도)
    double latDegreeDiff = searchRadius / 111.0;
    double lngDegreeDiff = searchRadius / (111.0 * Math.cos(Math.toRadians(latitude)));

    double minLat = latitude - latDegreeDiff;
    double maxLat = latitude + latDegreeDiff;
    double minLng = longitude - lngDegreeDiff;
    double maxLng = longitude + lngDegreeDiff;

    // 3. 1차: Bounding Box로 후보군 추출 (DB 인덱스 활용)
    List<Popup> candidates =
        popupRepository.findByLatitudeBetweenAndLongitudeBetween(minLat, maxLat, minLng, maxLng);

    // 4. 2차: 하버사인 공식 계산 및 필터링, 거리순 정렬
    return candidates.stream()
        .filter(popup -> popup.getLatitude() != null && popup.getLongitude() != null)
        .map(
            popup -> {
              // BigDecimal -> double 변환
              double popupLat = popup.getLatitude().doubleValue();
              double popupLng = popup.getLongitude().doubleValue();

              double dist = calculateDistanceInKm(latitude, longitude, popupLat, popupLng);
              return Map.entry(popup, dist);
            })
        .filter(entry -> entry.getValue() <= searchRadius)
        .sorted(Comparator.comparingDouble(Map.Entry::getValue)) // 가까운 거리순 오름차순
        .map(
            entry -> {
              double roundedDist = Math.round(entry.getValue() * 100.0) / 100.0; // 소수 둘째 자리 반올림
              return PopupResponse.from(entry.getKey(), roundedDist);
            })
        .toList();
  }

  // 하버사인(Haversine) 공식: 두 위경도 좌표 간 직선거리(km) 계산
  private double calculateDistanceInKm(double lat1, double lon1, double lat2, double lon2) {
    final int EARTH_RADIUS_KM = 6371;

    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);

    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);

    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return EARTH_RADIUS_KM * c;
  }
}
