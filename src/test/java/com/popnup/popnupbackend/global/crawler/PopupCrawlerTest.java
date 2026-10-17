package com.popnup.popnupbackend.global.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class PopupCrawlerTest {

  private MockRestServiceServer server;
  private PopupCrawler crawler;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    crawler = new PopupCrawler(JsonMapper.builder().build(), builder.build());
  }

  private void respondWith(String json) {
    server
        .expect(
            request ->
                assertThat(request.getURI().toString())
                    .startsWith("https://api.popply.co.kr/api/store/list/?date="))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("API 응답을 팝업 정보로 바꾼다 (주소 합치기, 설명 만들기, 이름 없는 항목 건너뛰기)")
  void crawlPopups_mapsFields() {
    respondWith(
        """
        {"data":[
          {"name":"A 팝업","topLevelAddress":"서울","address":"서울 성동구 1","detailAddress":"2층",
           "startDate":"2026-10-01","endDate":"2026-10-31","mainBrand":"브랜드","thumbnails":"https://img/a.png"},
          {"name":"","address":"이름 없는 항목"},
          {"name":"B 팝업","address":"서울 강남구","detailAddress":"","mainBrand":""}
        ]}
        """);

    List<CrawledPopupDto> popups = crawler.crawlPopups();

    assertThat(popups).hasSize(2);
    CrawledPopupDto first = popups.get(0);
    assertThat(first.getTitle()).isEqualTo("A 팝업");
    assertThat(first.getLocation()).isEqualTo("서울 성동구 1 2층");
    assertThat(first.getDescription()).isEqualTo("[브랜드] A 팝업");
    assertThat(first.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(first.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(first.getImageUrl()).isEqualTo("https://img/a.png");

    CrawledPopupDto second = popups.get(1);
    assertThat(second.getLocation()).isEqualTo("서울 강남구");
    assertThat(second.getDescription()).isEqualTo("B 팝업 공식 팝업스토어");
    assertThat(second.getStartDate()).isEqualTo(LocalDate.now());
    assertThat(second.getEndDate()).isEqualTo(LocalDate.now().plusMonths(1));
  }

  @Test
  @DisplayName("주소가 모두 비어 있으면 '주소 정보 없음'으로 채운다")
  void crawlPopups_blankAddress() {
    respondWith("{\"data\":[{\"name\":\"C 팝업\"}]}");

    assertThat(crawler.crawlPopups())
        .singleElement()
        .satisfies(popup -> assertThat(popup.getLocation()).isEqualTo("주소 정보 없음"));
  }

  @Test
  @DisplayName("테스트용으로 최대 10건까지만 가져온다")
  void crawlPopups_limitsToTen() {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      items.append(i == 0 ? "" : ",").append("{\"name\":\"팝업").append(i).append("\"}");
    }
    respondWith("{\"data\":[" + items + "]}");

    assertThat(crawler.crawlPopups()).hasSize(10);
  }

  @Test
  @DisplayName("data가 배열이 아니거나 API가 실패하면 빈 목록을 돌려준다 (예외를 던지지 않는다)")
  void crawlPopups_failureReturnsEmpty() {
    respondWith("{\"data\":{\"unexpected\":true}}");
    assertThat(crawler.crawlPopups()).isEmpty();

    server.reset();
    server.expect(request -> {}).andRespond(withServerError());
    assertThat(crawler.crawlPopups()).isEmpty();
  }
}
