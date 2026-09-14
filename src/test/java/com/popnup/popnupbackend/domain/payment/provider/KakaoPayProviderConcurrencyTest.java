package com.popnup.popnupbackend.domain.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.payment.dto.request.KakaoPayOrderRequest;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayReadyResponse;
import com.popnup.popnupbackend.domain.payment.repository.PaymentRepository;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@ActiveProfiles("test")
class KakaoPayProviderConcurrencyTest {

  @Autowired private KakaoPayProvider kakaoPayProvider;

  @Autowired private PaymentRepository paymentRepository;

  @Autowired private ReservationRepository reservationRepository;

  @Autowired private EntityManager entityManager;

  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private RestTemplate restTemplate;

  @Test
  void 동시에_ready를_호출하면_Payment는_하나만_생성된다() throws Exception {

    // 1. 테스트용 예약 생성
    Long reservationId =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  Member member =
                      Member.createLocal("ready-concurrent@test.com", "password", "동시성테스트회원");

                  entityManager.persist(member);

                  Popup popup =
                      Popup.builder()
                          .title("동시성 테스트 팝업")
                          .description("동시성 테스트")
                          .category(PopupCategory.FASHION)
                          .region("대구")
                          .address("대구광역시")
                          .startDate(LocalDate.now())
                          .endDate(LocalDate.now().plusDays(10))
                          .isFree(false)
                          .price(10000)
                          .status(PopupStatus.OPEN)
                          .build();

                  entityManager.persist(popup);

                  Schedule schedule =
                      Schedule.createSchedule(
                          popup,
                          LocalDate.now().plusDays(1),
                          LocalTime.of(14, 0),
                          LocalTime.of(15, 0),
                          100);

                  entityManager.persist(schedule);

                  Reservation reservation =
                      Reservation.createReservation(
                          "R20260914-READY-CONCURRENT", member, schedule, 1);

                  entityManager.persist(reservation);

                  entityManager.flush();

                  return reservation.getId();
                });

    // 2. 카카오페이 응답 Mock
    KakaoPayReadyResponse kakaoResponse = new KakaoPayReadyResponse();

    when(restTemplate.postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/ready"),
            any(),
            eq(KakaoPayReadyResponse.class)))
        .thenReturn(ResponseEntity.ok(kakaoResponse));

    // 3. 두 스레드 준비
    int threadCount = 2;

    ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);

    List<Future<?>> futures = new ArrayList<>();

    // 4. 동시에 ready() 호출
    for (int i = 0; i < threadCount; i++) {

      futures.add(
          executorService.submit(
              () -> {
                try {
                  ready.countDown();
                  start.await();

                  AuthUser authUser =
                      new AuthUser(1L, "ready-concurrent@test.com", "동시성테스트회원", null);

                  SecurityContext securityContext = SecurityContextHolder.createEmptyContext();

                  securityContext.setAuthentication(
                      new UsernamePasswordAuthenticationToken(authUser, null));

                  SecurityContextHolder.setContext(securityContext);

                  KakaoPayOrderRequest request = new KakaoPayOrderRequest();

                  /*
                   * DTO에 setter가 없으므로
                   * ObjectMapper로 생성
                   */
                  request =
                      new tools.jackson.databind.ObjectMapper()
                          .readValue(
                              """
                                                            {
                                                              "reservationId": %d,
                                                              "itemName": "동시성 테스트",
                                                              "quantity": 1,
                                                              "totalPrice": 10000
                                                            }
                                                            """
                                  .formatted(reservationId),
                              KakaoPayOrderRequest.class);

                  kakaoPayProvider.ready(request);

                } catch (InterruptedException e) {

                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);

                } catch (Exception e) {

                  throw new RuntimeException(e);

                } finally {

                  SecurityContextHolder.clearContext();
                }
              }));
    }

    // 5. 두 스레드가 모두 준비될 때까지 대기
    ready.await();

    // 동시에 출발
    start.countDown();

    int successCount = 0;
    int failureCount = 0;

    for (Future<?> future : futures) {

      try {
        future.get();
        successCount++;

      } catch (ExecutionException e) {
        failureCount++;
      }
    }

    executorService.shutdown();

    // 6. 최종 Payment 개수 확인
    long paymentCount = paymentRepository.countByReservationId(reservationId);

    assertThat(paymentCount).isEqualTo(1L);

    assertThat(successCount + failureCount).isEqualTo(2);
  }
}
