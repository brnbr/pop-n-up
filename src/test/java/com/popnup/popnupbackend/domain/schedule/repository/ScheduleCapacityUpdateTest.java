package com.popnup.popnupbackend.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.config.JpaConfig;
import com.popnup.popnupbackend.global.config.QuerydslConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({QuerydslConfig.class, JpaConfig.class})
class ScheduleCapacityUpdateTest {

  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private TestEntityManager em;

  private Popup popup;

  @BeforeEach
  void setUp() {
    popup =
        em.persist(
            Popup.builder()
                .title("테스트 팝업")
                .category(PopupCategory.ETC)
                .region("서울")
                .address("서울 성동구")
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(30))
                .build());
  }

  // 예약 인원이 booked명인 회차
  private Long scheduleWith(int hour, int booked) {
    Schedule s =
        em.persist(
            Schedule.createSchedule(
                popup,
                LocalDate.now().plusDays(1),
                LocalTime.of(hour, 0),
                LocalTime.of(hour + 1, 0),
                10));
    em.flush();
    scheduleRepository.tryIncreaseCapacity(s.getId(), booked);
    return s.getId();
  }

  private int nowCapacity(Long id) {
    em.clear();
    return scheduleRepository.findById(id).orElseThrow().getNowCapacity();
  }

  @Test
  @DisplayName("여러 회차의 좌석을 한 문장으로 회차마다 다른 인원만큼 돌려준다")
  void decreaseCapacities_perSchedule() {
    Long a = scheduleWith(10, 5);
    Long b = scheduleWith(12, 3);
    Long untouched = scheduleWith(14, 4);

    assertThat(scheduleRepository.tryDecreaseCapacities(Map.of(a, 2, b, 3))).isEqualTo(2);

    assertThat(nowCapacity(a)).isEqualTo(3);
    assertThat(nowCapacity(b)).isZero();
    assertThat(nowCapacity(untouched)).isEqualTo(4);
  }

  @Test
  @DisplayName("남은 예약 인원보다 많이 돌려주려는 회차는 바꾸지 않고 반환 수에서 뺀다")
  void decreaseCapacities_overReturn_skipsThatSchedule() {
    Long a = scheduleWith(10, 5);
    Long b = scheduleWith(12, 1);

    assertThat(scheduleRepository.tryDecreaseCapacities(Map.of(a, 2, b, 2))).isEqualTo(1);

    assertThat(nowCapacity(a)).isEqualTo(3);
    assertThat(nowCapacity(b)).isEqualTo(1);
  }
}
