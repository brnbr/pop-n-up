package com.popnup.popnupbackend.global.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class PopupCrawler {

  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  public List<CrawledPopupDto> crawlPopups() {
    List<CrawledPopupDto> popups = new ArrayList<>();
    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    String apiUrl = "https://api.popply.co.kr/api/store/list/?date=" + today;

    try {
      log.info("[PopupCrawler] Popply 실시간 API 데이터 수집 시도: {}", apiUrl);

      String response =
          restClient
              .get()
              .uri(apiUrl)
              .header(
                  "User-Agent",
                  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
              .retrieve()
              .body(String.class);

      JsonNode rootNode = objectMapper.readTree(response);
      JsonNode dataList = rootNode.path("data");

      if (dataList.isArray()) {
        for (JsonNode item : dataList) {
          String name = item.path("name").asText();
          if (name.isBlank()) continue;

          // 실제 JSON에 존재하는 필드명 매핑
          String region = item.path("topLevelAddress").asText("서울");
          String roadAddress = item.path("address").asText();
          String detailAddress = item.path("detailAddress").asText();
          String fullAddress =
              detailAddress.isBlank() ? roadAddress : roadAddress + " " + detailAddress;

          String startDateStr = item.path("startDate").asText();
          String endDateStr = item.path("endDate").asText();

          String mainBrand = item.path("mainBrand").asText();
          String description =
              mainBrand.isBlank() ? name + " 공식 팝업스토어" : "[" + mainBrand + "] " + name;

          String thumbnail = item.path("thumbnails").asText();

          popups.add(
              CrawledPopupDto.builder()
                  .title(name)
                  .location(fullAddress.isBlank() ? "주소 정보 없음" : fullAddress)
                  .description(description)
                  .startDate(
                      startDateStr.isBlank() ? LocalDate.now() : LocalDate.parse(startDateStr))
                  .endDate(
                      endDateStr.isBlank()
                          ? LocalDate.now().plusMonths(1)
                          : LocalDate.parse(endDateStr))
                  .imageUrl(thumbnail)
                  .build());

          // 테스트용 10건만 등록
          if (popups.size() >= 10) break;
        }
      }

      log.info("[PopupCrawler] 실제 API에서 {}건의 팝업 데이터 적재 완료", popups.size());

    } catch (Exception e) {
      log.warn("[PopupCrawler] Popply API 파싱 실패: {}", e.getMessage());
    }

    return popups;
  }
}
