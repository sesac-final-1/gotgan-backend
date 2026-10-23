package com.sesac.gojigi.order.saga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 결제 대기 만료 · Saga 복구 대상 고르기 · 결제 확인용 조회 — MySQL 8.4 + Flyway */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, OrderSagaTxService.class,
        OrderSagaTxServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋 (스케줄러와 같은 조건)
class OrderSagaTxServiceIntegrationTest {

    private static final long BUYER = 501L;
    /** 멈춘 것으로 볼 기준 — 아래 테스트는 updated_at 을 이보다 오래전으로 바꿔 "멈춘" Saga 를 만든다 */
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2021, 1, 1, 0, 0);
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));   // 2026-10-22 12:00 KST
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private OrderSagaTxService sagaTx;
    @Autowired private JdbcTemplate jdbc;

    private Order order() {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(81L, 1)), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, 0L);
        OrderPricing.Result priced = OrderPricing.calculate(List.of(new OrderPricing.Line(new ProductOptionResponse(81L,
                        81L, "상품 81", null, 801L, 301L, 3L, 10_000L, 0L, 10_000L, 100, true, null), 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 0L);
        return orderTx.createPending(new UserSummaryResponse(BUYER, "buyer@example.com", "홍길동", null), request,
                priced, Map.of(801L, "곳간상회")).order();
    }

    /** step 까지 진행한 STARTED Saga */
    private SagaInstance saga(SagaStep step) {
        SagaInstance saga = sagaTx.startPlaceOrder(order());
        if (step != SagaStep.ORDER_CREATED) {
            sagaTx.advance(saga.getId(), step);
        }
        return saga;
    }

    private void stalled(SagaInstance saga) {
        jdbc.update("update saga_instance set updated_at = ? where saga_instance_id = ?", LONG_AGO, saga.getId());
    }

    private static List<Long> ids(List<SagaInstance> sagas) {
        return sagas.stream().map(SagaInstance::getId).toList();
    }

    @Test
    @DisplayName("결제 대기 만료 대상 — 기준보다 오래된 PAYMENT_READY · PAYMENT_VERIFIED 만, 주문을 함께 읽는다")
    void findPaymentWaiting() {
        // given
        SagaInstance waiting = saga(SagaStep.PAYMENT_READY);
        stalled(waiting);
        SagaInstance verified = saga(SagaStep.PAYMENT_VERIFIED);
        stalled(verified);
        SagaInstance fresh = saga(SagaStep.PAYMENT_READY);
        SagaInstance beforePayment = saga(SagaStep.STOCK_RESERVED);
        stalled(beforePayment);
        SagaInstance completed = saga(SagaStep.PAYMENT_READY);
        sagaTx.complete(completed.getId());
        stalled(completed);

        // when
        List<SagaInstance> found = sagaTx.findPaymentWaiting(CUTOFF, 1_000);

        // then
        assertThat(ids(found)).contains(waiting.getId(), verified.getId())
                .doesNotContain(fresh.getId(), beforePayment.getId(), completed.getId());
        assertThat(found.get(0).getOrder().getOrderNo()).isNotBlank();   // 트랜잭션 밖에서도 주문을 읽을 수 있음
    }

    @Test
    @DisplayName("복구 대상 — 결제 준비 전 단계에서 멈춘 STARTED, 재시도 시각이 지났거나 시각 없이 멈춘 COMPENSATING")
    void findRecoverable() {
        // given
        SagaInstance stalledBeforePayment = saga(SagaStep.STOCK_RESERVED);
        stalled(stalledBeforePayment);
        SagaInstance freshBeforePayment = saga(SagaStep.ORDER_CREATED);
        SagaInstance stalledWaiting = saga(SagaStep.PAYMENT_READY);   // 결제 대기는 만료 스케줄러 몫
        stalled(stalledWaiting);

        SagaInstance retryDue = saga(SagaStep.PAYMENT_READY);
        sagaTx.startCompensating(retryDue.getId(), "보상 실패");
        sagaTx.scheduleRetry(retryDue.getId(), "PAYMENT_CANCEL_PENDING");   // 12:01
        jdbc.update("update saga_instance set next_retry_at = ? where saga_instance_id = ?",
                LocalDateTime.of(2026, 10, 22, 11, 59), retryDue.getId());
        SagaInstance retryLater = saga(SagaStep.PAYMENT_READY);
        sagaTx.startCompensating(retryLater.getId(), "보상 실패");
        sagaTx.scheduleRetry(retryLater.getId(), "PAYMENT_CANCEL_PENDING");   // 12:01 — 아직
        SagaInstance compensatingStalled = saga(SagaStep.POINT_USED);    // 보상 도중 파드 종료
        sagaTx.startCompensating(compensatingStalled.getId(), "재고 부족");
        stalled(compensatingStalled);

        // when
        List<SagaInstance> found = sagaTx.findRecoverable(CUTOFF, 1_000);

        // then
        assertThat(ids(found)).contains(stalledBeforePayment.getId(), retryDue.getId(), compensatingStalled.getId())
                .doesNotContain(freshBeforePayment.getId(), stalledWaiting.getId(), retryLater.getId());
    }

    @Test
    @DisplayName("결제 확인용 조회 — 본인 주문 · 주문 Saga · 상품주문, 다른 회원의 주문번호는 404")
    void getMineAndSaga() {
        // given
        SagaInstance saga = saga(SagaStep.PAYMENT_READY);
        Order order = orderTx.getMine(BUYER, saga.getOrder().getOrderNo());

        // when · then
        assertThat(sagaTx.getPlaceOrder(order.getId()).getSagaId()).isEqualTo(saga.getSagaId());
        assertThat(orderTx.getItems(order.getId())).singleElement()
                .satisfies(item -> assertThat(item.getProductOptionId()).isEqualTo(81L));
        assertThatThrownBy(() -> orderTx.getMine(BUYER + 1, order.getOrderNo()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
        assertThatThrownBy(() -> sagaTx.getPlaceOrder(-1L)).isInstanceOf(IllegalStateException.class);
    }
}
