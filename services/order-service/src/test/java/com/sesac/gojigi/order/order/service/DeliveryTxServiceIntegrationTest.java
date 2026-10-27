package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.common.config.SchedulingConfig;
import com.sesac.gojigi.order.common.event.EventOutboxService;
import com.sesac.gojigi.order.common.event.OutboxEvent;
import com.sesac.gojigi.order.common.event.OutboxEventRepository;
import com.sesac.gojigi.order.common.event.SnsEventPublisher;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.dto.response.OrderItemResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.repository.OrderItemHistoryRepository;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 발주확인 · 송장 등록 · 배송완료 · 자동 배송완료 대상 + 이벤트 아웃박스(커밋 후 발행 · 실패 재발행) — MySQL 8.4 + Flyway */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, DeliveryTxService.class, EventOutboxService.class,
        SchedulingConfig.class, DeliveryTxServiceIntegrationTest.Beans.class })   // 발행기 @SchedulerLock 에 ShedLock 저장소
@ActiveProfiles("test")
// retry-after 음수: 고정 시계(12:00)보다 늦게 만들어진 행(created_at 은 DB 실제 시각)도 발행기가 바로 고르게
@TestPropertySource(properties = { "app.order.auto-confirm-after=7d", "app.events.outbox.retry-after=-3650d",
        "app.events.outbox.max-attempts=2" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋 (AFTER_COMMIT 발행이 돌게)
class DeliveryTxServiceIntegrationTest {

    private static final long STORE = 961L;
    private static final long SELLER = 70L;

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"), ZoneId.of("Asia/Seoul"));   // 2026-10-26 12:00 KST
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private DeliveryTxService deliveryTx;
    @Autowired private EventOutboxService outbox;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderItemHistoryRepository historyRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private SnsEventPublisher sns;

    /** 결제 완료 상품주문 1건 (스토어 961) */
    private OrderItem paidItem() {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(96L, 1)), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, 0L);
        OrderPricing.Result priced = OrderPricing.calculate(List.of(new OrderPricing.Line(new ProductOptionResponse(96L,
                        96L, "상품 96", "특", STORE, 301L, 3L, 20_000L, 0L, 20_000L, 100, true, null), 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 0L);
        Order order = orderTx.createPending(new UserSummaryResponse(4L, "buyer@example.com", "홍길동", null), request,
                priced, Map.of(STORE, "곳간상회")).order();
        orderTx.markPaid(order.getId());
        return orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).get(0);
    }

    private List<OutboxEvent> events(OrderItem item) {
        return outboxRepository.findAll().stream().filter(e -> e.getAggregateId().equals(item.getItemNo())).toList();
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("발주확인 → 송장 등록(mock 송장번호) → 배송완료 — 상태 · 시각 · 이력, 송장 · 배송완료 이벤트를 커밋 후 발행")
    void prepareShipDeliver() throws Exception {
        // given
        OrderItem item = paidItem();

        // when
        deliveryTx.prepare(STORE, SELLER, item.getId());
        OrderItemResponse shipped = deliveryTx.ship(STORE, SELLER, item.getId(), "CJ대한통운", null);
        OrderItemResponse delivered = deliveryTx.deliver(STORE, SELLER, item.getId());

        // then
        assertThat(shipped.status()).isEqualTo(OrderItemStatus.SHIPPING);
        assertThat(shipped.trackingNo()).isEqualTo("6512" + String.format("%08d", item.getId()));
        assertThat(shipped.shippedAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
        assertThat(delivered.status()).isEqualTo(OrderItemStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
        assertThat(historyRepository.findByOrderItemIdOrderByIdAsc(item.getId()))
                .extracting(OrderItemHistory::getToStatus)
                .containsExactly(OrderItemStatus.PREPARING, OrderItemStatus.SHIPPING, OrderItemStatus.DELIVERED);

        List<OutboxEvent> events = events(item);
        assertThat(events).extracting(OutboxEvent::getEventType).containsExactly("OrderItemShipped", "OrderItemDelivered");
        assertThat(events).allSatisfy(e -> assertThat(e.isSent()).isTrue());
        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(sns).send(eq("OrderItemDelivered"), eq(item.getItemNo()), anyString(), envelope.capture());
        JsonNode json = jsonMapper.readTree(envelope.getValue());
        assertThat(json.get("eventType").asString()).isEqualTo("OrderItemDelivered");
        assertThat(json.get("aggregateId").asString()).isEqualTo(item.getItemNo());
        assertThat(json.get("payload").get("courier").asString()).isEqualTo("CJ대한통운");
        assertThat(json.get("payload").get("autoConfirmAt").asString()).startsWith("2026-11-02T12:00");
    }

    @Test
    @DisplayName("거절 — 남의 스토어 404 · 지금 상태에서 안 되는 전이 409 (중복 송장 등록 · 배송준비 전 송장)")
    void rejects() {
        // given
        OrderItem item = paidItem();

        // when · then
        assertCode(() -> deliveryTx.prepare(STORE + 1, SELLER, item.getId()), OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        assertCode(() -> deliveryTx.ship(STORE, SELLER, item.getId(), "CJ대한통운", "1234567890"),
                OrderErrorCode.ORDER_INVALID_STATUS);
        deliveryTx.prepare(STORE, SELLER, item.getId());
        deliveryTx.ship(STORE, SELLER, item.getId(), "롯데택배", "2470-1234-5678");
        assertCode(() -> deliveryTx.ship(STORE, SELLER, item.getId(), "롯데택배", "999999"),
                OrderErrorCode.ORDER_INVALID_STATUS);
        assertThat(orderItemRepository.findById(item.getId()).orElseThrow().getTrackingNo()).isEqualTo("2470-1234-5678");
        assertCode(() -> deliveryTx.deliverBySystem(-1L), OrderErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("자동 배송완료 — 기준 시각 전에 발송된 배송중만 고르고, 시스템이 배송완료 (이력 SYSTEM)")
    void autoDeliver() {
        // given
        OrderItem old = paidItem();
        deliveryTx.prepare(STORE, SELLER, old.getId());
        deliveryTx.ship(STORE, SELLER, old.getId(), "CJ대한통운", null);
        jdbc.update("update order_item set shipped_at = ? where order_item_id = ?",
                LocalDateTime.of(2026, 10, 26, 10, 0), old.getId());
        OrderItem fresh = paidItem();
        deliveryTx.prepare(STORE, SELLER, fresh.getId());
        deliveryTx.ship(STORE, SELLER, fresh.getId(), "CJ대한통운", null);

        // when
        List<Long> due = deliveryTx.findShippedBefore(LocalDateTime.of(2026, 10, 26, 11, 0), 1_000);
        deliveryTx.deliverBySystem(old.getId());

        // then
        assertThat(due).contains(old.getId()).doesNotContain(fresh.getId());
        assertThat(orderItemRepository.findById(old.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderItemStatus.DELIVERED);
        assertThat(historyRepository.findByOrderItemIdOrderByIdAsc(old.getId())).last()
                .satisfies(h -> assertThat(h.getActorId()).isNull());
    }

    @Test
    @DisplayName("SNS 발행 실패 — 행은 NEW 로 남고 시도 횟수 · 사유를 기록, 발행기가 다시 보내면 SENT")
    void outbox_retriesFailedPublish() {
        // given — 커밋 직후 발행 실패
        OrderItem item = paidItem();
        deliveryTx.prepare(STORE, SELLER, item.getId());
        willThrow(new IllegalStateException("sns down")).given(sns)
                .send(eq("OrderItemShipped"), anyString(), anyString(), anyString());
        deliveryTx.ship(STORE, SELLER, item.getId(), "CJ대한통운", null);
        OutboxEvent failed = events(item).get(0);
        assertThat(failed.isSent()).isFalse();
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("sns down");

        // when — 한 번 더 실패(한도 2 → ERROR 로그) 후 복구
        outbox.publishPending();
        willThrow(new IllegalStateException("still down")).willDoNothing().given(sns)
                .send(eq("OrderItemShipped"), anyString(), anyString(), anyString());
        outbox.publishPending();
        outbox.publishPending();

        // then
        OutboxEvent sent = outboxRepository.findById(failed.getId()).orElseThrow();
        assertThat(sent.isSent()).isTrue();
        assertThat(sent.getLastError()).isNull();
        assertThat(sent.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("구매확정 — 구매자 본인 배송완료만 CONFIRMED · confirmedAt · 이력, OrderItemConfirmed 커밋 후 발행, 자동 대상 · 시스템 확정")
    void confirm() throws Exception {
        // given — 배송완료 2건
        OrderItem mine = paidItem();
        deliveryTx.prepare(STORE, SELLER, mine.getId());
        deliveryTx.ship(STORE, SELLER, mine.getId(), "CJ대한통운", null);
        String orderNo = mine.getItemNo().substring(0, mine.getItemNo().lastIndexOf('-'));
        assertCode(() -> deliveryTx.confirm(4L, orderNo, mine.getId()), OrderErrorCode.ORDER_INVALID_STATUS);   // 배송중
        deliveryTx.deliver(STORE, SELLER, mine.getId());
        OrderItem old = paidItem();
        deliveryTx.prepare(STORE, SELLER, old.getId());
        deliveryTx.ship(STORE, SELLER, old.getId(), "CJ대한통운", null);
        deliveryTx.deliver(STORE, SELLER, old.getId());
        jdbc.update("update order_item set delivered_at = ? where order_item_id = ?",
                LocalDateTime.of(2026, 10, 18, 12, 0), old.getId());

        // when
        assertCode(() -> deliveryTx.confirm(5L, orderNo, mine.getId()), OrderErrorCode.ORDER_ITEM_NOT_FOUND);   // 다른 회원
        assertCode(() -> deliveryTx.confirm(4L, "20260101-000001", mine.getId()), OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        OrderItemResponse confirmed = deliveryTx.confirm(4L, orderNo, mine.getId());
        List<Long> due = deliveryTx.findDeliveredBefore(LocalDateTime.of(2026, 10, 19, 12, 0), 1_000);
        deliveryTx.confirmBySystem(old.getId());

        // then
        assertThat(confirmed.status()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(confirmed.confirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
        assertCode(() -> deliveryTx.confirm(4L, orderNo, mine.getId()), OrderErrorCode.ORDER_INVALID_STATUS);   // 이미 확정
        assertThat(due).contains(old.getId()).doesNotContain(mine.getId());
        assertThat(orderItemRepository.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(historyRepository.findByOrderItemIdOrderByIdAsc(old.getId())).last()
                .satisfies(h -> assertThat(h.getActorId()).isNull());
        assertThat(events(mine)).extracting(OutboxEvent::getEventType).containsExactly("OrderItemShipped",
                "OrderItemDelivered", "OrderItemConfirmed");
        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(sns).send(eq("OrderItemConfirmed"), eq(mine.getItemNo()), anyString(), envelope.capture());
        JsonNode payload = jsonMapper.readTree(envelope.getValue()).get("payload");
        assertThat(payload.get("itemAmt").asLong()).isEqualTo(20_000L);
        assertThat(payload.get("storeId").asLong()).isEqualTo(STORE);
        assertThat(payload.get("confirmedAt").asString()).startsWith("2026-10-26T12:00");
        assertCode(() -> deliveryTx.confirmBySystem(-1L), OrderErrorCode.ORDER_ITEM_NOT_FOUND);
    }
}
