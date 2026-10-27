package com.sesac.gojigi.order.saga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.service.ClaimTxService;
import com.sesac.gojigi.order.common.config.SchedulingConfig;
import com.sesac.gojigi.order.common.event.EventOutboxService;
import com.sesac.gojigi.order.common.event.OutboxEvent;
import com.sesac.gojigi.order.common.event.OutboxEventRepository;
import com.sesac.gojigi.order.common.event.SnsEventPublisher;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.service.OrderEventRecorder;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 주문 이벤트 발행 — OrderPaid(결제 확정) · OrderItemCancelled(취소 완료)를 업무 트랜잭션 안에서 아웃박스에, 커밋 후 SNS (MySQL 8.4) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, OrderEventRecorder.class, CancelItemTxService.class,
        ClaimEventRecorder.class, ClaimTxService.class, OrderSagaTxService.class, EventOutboxService.class,
        SchedulingConfig.class, OrderEventOutboxIntegrationTest.Beans.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋 (AFTER_COMMIT 발행이 돌게)
class OrderEventOutboxIntegrationTest {

    private static final AtomicLong BUYERS = new AtomicLong(1_301L);

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private CancelItemTxService cancelTx;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private JsonMapper jsonMapper;
    @MockitoBean private SnsEventPublisher sns;

    /** 결제 완료 주문 — 스토어 1301: 사과 20,000(특) + 배 10,000(옵션 없음) · 포인트 3,000 사용 */
    private Order paidOrder(long buyer) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(131L, 1)), "김수령",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, 3_000L);
        OrderPricing.Result priced = OrderPricing.calculate(List.of(
                new OrderPricing.Line(new ProductOptionResponse(131L, 131L, "사과", "특", 1_301L, 301L, 3L, 20_000L, 0L,
                        20_000L, 100, true, null), 1),
                new OrderPricing.Line(new ProductOptionResponse(132L, 132L, "배", null, 1_301L, 301L, 3L, 10_000L, 0L,
                        10_000L, 100, true, null), 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 3_000L);
        Order order = orderTx.createPending(new UserSummaryResponse(buyer, "buyer@example.com", "홍길동", "곳간지기"),
                request, priced, Map.of(1_301L, "곳간상회")).order();
        orderTx.markPaid(order.getId());
        return order;
    }

    private OutboxEvent only(String eventType, String aggregateId) {
        return outboxRepository.findAll().stream()
                .filter(e -> e.getEventType().equals(eventType) && e.getAggregateId().equals(aggregateId))
                .reduce((a, b) -> { throw new AssertionError("이벤트가 둘 이상"); }).orElseThrow();
    }

    private JsonNode sentPayload(String eventType, String aggregateId) {
        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(sns).send(eq(eventType), eq(aggregateId), anyString(), envelope.capture());
        return jsonMapper.readTree(envelope.getValue()).get("payload");
    }

    @Test
    @DisplayName("결제 확정 — OrderPaid(주문 단위, 주문자 · 사용 포인트 · 상품주문별 적립 포인트 · 금액)를 같은 트랜잭션에, 커밋 후 SNS")
    void orderPaid() {
        // given · when
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer);

        // then
        OutboxEvent event = only("OrderPaid", order.getOrderNo());
        assertThat(event.getAggregateType()).isEqualTo(OutboxEvent.AggregateType.ORDER);
        assertThat(event.isSent()).isTrue();
        JsonNode payload = sentPayload("OrderPaid", order.getOrderNo());
        assertThat(payload.get("buyerId").asLong()).isEqualTo(buyer);
        assertThat(payload.get("ordererName").asString()).isEqualTo("홍길동");
        assertThat(payload.get("ordererEmail").asString()).isEqualTo("buyer@example.com");
        assertThat(payload.get("usedPoint").asLong()).isEqualTo(3_000L);
        assertThat(payload.get("payAmt").asLong()).isEqualTo(30_000L + 3_000L - 3_000L);
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        JsonNode first = payload.get("items").get(0);
        assertThat(payload.get("items")).hasSize(2);
        assertThat(first.get("orderItemId").asLong()).isEqualTo(items.get(0).getId());
        assertThat(first.get("earnedPoint").asLong()).isEqualTo(items.get(0).getEarnedPoint());
        assertThat(first.get("productName").asString()).isEqualTo("사과");
        assertThat(first.get("optionName").asString()).isEqualTo("특");
        assertThat(first.get("itemAmt").asLong()).isEqualTo(20_000L);
        assertThat(payload.get("items").get(1).get("optionName").isNull()).isTrue();
    }

    @Test
    @DisplayName("취소 완료 — OrderItemCancelled(상품주문 단위, 환불액 · 돌려준 사용 포인트 · 회수한 적립 포인트), Saga 진행 중에는 없다")
    void orderItemCancelled() {
        // given
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer);
        OrderItem item = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).get(0);
        CancelItemTxService.CancelContext ctx = cancelTx.open(buyer, order.getOrderNo(), item.getId(),
                ClaimReasonCode.CHANGE_OF_MIND, "다시 주문");
        assertThat(outboxRepository.findAll()).noneMatch(e -> e.getAggregateId().equals(item.getItemNo()));

        // when
        cancelTx.finish(ctx.sagaInstanceId());

        // then
        OutboxEvent event = only("OrderItemCancelled", item.getItemNo());
        assertThat(event.getAggregateType()).isEqualTo(OutboxEvent.AggregateType.ORDER_ITEM);
        JsonNode payload = sentPayload("OrderItemCancelled", item.getItemNo());
        assertThat(payload.get("orderNo").asString()).isEqualTo(order.getOrderNo());
        assertThat(payload.get("buyerId").asLong()).isEqualTo(buyer);
        assertThat(payload.get("productName").asString()).isEqualTo("사과");
        assertThat(payload.get("refundAmt").asLong()).isEqualTo(ctx.refundAmt());
        assertThat(payload.get("restoredPoint").asLong()).isEqualTo(item.getUsedPoint());
        assertThat(payload.get("revokedPoint").asLong()).isEqualTo(item.getEarnedPoint());
    }
}
