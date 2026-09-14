package com.popnup.popnupbackend.global.crawler;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleBatchCreateRequest;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PopupDataInitializer implements ApplicationRunner {

  private final MemberRepository memberRepository;
  private final PopupCrawler popupCrawler;
  private final PopupRepository popupRepository;
  private final ScheduleService scheduleService;

  @Override
  public void run(ApplicationArguments args) {
    // 1. 테스트용 더미 회원 생성
    if (memberRepository.count() == 0) {
      Member testMember = Member.createLocal("test@popnup.com", "password123!", "테스터");
      memberRepository.save(testMember);
      log.info("[Initializer] 테스트용 더미 회원(ID: 1) 생성 완료");
    }

    // 2. 기존 팝업이 이미 있으면 중복 생성 건너뜀
    if (popupRepository.count() > 0) {
      log.info("[Initializer] 기존 팝업 데이터가 이미 존재합니다. 시딩 생략.");
      return;
    }

    log.info("[Initializer] 팝업 크롤링 및 가상 슬롯 자동 생성 파이프라인 가동...");
    List<CrawledPopupDto> crawledPopups = popupCrawler.crawlPopups();

    for (CrawledPopupDto dto : crawledPopups) {
      Popup popup =
          Popup.builder()
              .title(dto.getTitle())
              .description(dto.getDescription())
              .category(PopupCategory.ETC)
              .region("서울")
              .address(dto.getLocation())
              .startDate(dto.getStartDate())
              .endDate(dto.getEndDate())
              .isFree(true)
              .price(0)
              .status(PopupStatus.OPEN)
              .build();

      Popup savedPopup = popupRepository.save(popup);

      // 오늘과 내일 날짜로 슬롯 일괄 생성
      createVirtualSchedules(savedPopup.getId(), LocalDate.now());
      createVirtualSchedules(savedPopup.getId(), LocalDate.now().plusDays(1));
    }

    log.info("[Initializer] 모든 팝업 및 예약 타임 슬롯 초기화 완료!");
  }

  private void createVirtualSchedules(Long popupId, LocalDate scheduleDate) {
    ScheduleBatchCreateRequest batchRequest =
        new ScheduleBatchCreateRequest(
            popupId, scheduleDate, LocalTime.of(10, 0), LocalTime.of(20, 0), 60, 15);

    try {
      int createdCount = scheduleService.createBatchSchedules(batchRequest);
      log.info(" - 팝업(ID: {}) 날짜({}) 슬롯 {}개 생성 완료", popupId, scheduleDate, createdCount);
    } catch (Exception e) {
      log.error(" - 팝업(ID: {}) 슬롯 생성 실패: {}", popupId, e.getMessage());
    }
  }
}
