package com.popnup.popnupbackend.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.config.JpaConfig;
import com.popnup.popnupbackend.global.config.QuerydslConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({QuerydslConfig.class, JpaConfig.class})
class ScheduleStatusUpdateTest {

  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private TestEntityManager em;

  @Test
  @DisplayName("회차를 읽은 뒤 예약이 반영돼도, 상태만 바꾸는 저장이 예약 인원(now_capacity)을 덮어쓰지 않는다")
  void statusUpdate_doesNotOverwriteCapacity() {
    Popup popup =
        em.persist(
            Popup.builder()
                .title("테스트 팝업")
                .category(PopupCategory.ETC)
                .region("서울")
                .address("서울 성동구")
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(30))
                .build());
    Schedule created =
        em.persist(
            Schedule.createSchedule(
                popup, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(11, 0), 10));
    em.flush();
    em.clear();

    // 관리자가 상태 변경을 위해 회차를 읽는다 (예약 0명 시점)
    Schedule loaded = scheduleRepository.findById(created.getId()).orElseThrow();
    // 그 사이 예약 3명이 조건부 UPDATE로 반영된다
    assertThat(scheduleRepository.tryIncreaseCapacity(created.getId(), 3)).isEqualTo(1);
    // 관리자 트랜잭션이 저장된다
    loaded.updateActiveStatus(false);
    em.flush();
    em.clear();

    Schedule after = scheduleRepository.findById(created.getId()).orElseThrow();
    assertThat(after.isActive()).isFalse();
    assertThat(after.getNowCapacity()).isEqualTo(3);
  }
}
