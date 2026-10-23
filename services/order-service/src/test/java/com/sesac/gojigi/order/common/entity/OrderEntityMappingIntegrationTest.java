package com.sesac.gojigi.order.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.cart.entity.CartItem;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.common.event.OutboxEvent;
import com.sesac.gojigi.order.order.entity.ActorType;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.entity.OrderStoreShipment;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStatus;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.entity.SagaType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 엔티티 ↔ orderdb 스키마(Flyway V1~V3) 매핑 확인 — 컨텍스트가 뜨면 ddl-auto: validate 통과,
 * 저장 후 다시 읽어 DB 기본값(created_at · version) · Enum 문자열 · JSON 컬럼이 맞는지 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OrderEntityMappingIntegrationTest {

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("주문 · 상품주문 · 이력 · 클레임 · Saga · Outbox · 장바구니를 저장하고 다시 읽는다")
    void persistAndReload_allEntities() {
        // given
        Order order = em.persist(Order.builder()
                .buyerId(4L).orderNo("20261022-000123")
                .ordererName("김구매").ordererDisplayName("곳간지기").ordererEmail("buyer@example.com")
                .receiverName("김구매").receiverPhone("010-1234-5678").zipcode("06236")
                .address1("서울 강남구 테헤란로 1").address2("101호").deliveryMemo("문 앞")
                .totalProductAmt(64_000L).totalShippingFee(3_000L).usedPoint(2_000L).payAmt(65_000L)
                .build());
        em.persist(OrderStoreShipment.of(order, 1L, 3_000L));
        OrderItem item = em.persist(OrderItem.builder()
                .order(order).storeId(1L).productId(3L).productOptionId(11L).categoryId(301L).topCategoryId(3L)
                .itemNo("20261022-000123-01").storeName("곳간상회").productName("제철 사과 3kg").optionName("특")
                .unitPrice(32_000L).quantity(2).itemAmt(64_000L).usedPoint(2_000L).earnedPoint(3_100L)
                .build());
        em.persist(OrderItemHistory.of(item, ActorType.SYSTEM, 99L, null, OrderItemStatus.PAYMENT_DONE, "결제 완료"));
        OrderClaim claim = em.persist(OrderClaim.builder()
                .orderItem(item).requesterId(4L).claimType(ClaimType.CANCEL).reasonCode(ClaimReasonCode.CHANGE_OF_MIND)
                .reasonDetail("단순 변심").refundAmt(62_000L).requestedAt(LocalDateTime.of(2026, 10, 22, 10, 0))
                .build());
        em.persist(SagaInstance.placeOrder(order, "6c1f0a6e-0b0e-4c5b-9a8b-2f3c8e7d1a10"));
        em.persist(SagaInstance.forClaim(claim, "7d2e1b7f-1c1f-4d6c-8b9c-3a4d9f8e2b21", SagaType.CANCEL_ITEM));
        em.persist(OutboxEvent.of("9b1d2c3e-0000-4000-8000-000000000001", OutboxEvent.AggregateType.ORDER,
                order.getOrderNo(), "OrderPaid", "{\"orderNo\":\"20261022-000123\"}"));
        em.persist(CartItem.builder().userId(4L).productId(3L).productOptionId(11L).quantity(1).build());
        em.flush();
        em.clear();

        // when
        Order foundOrder = em.find(Order.class, order.getId());
        OrderItem foundItem = em.find(OrderItem.class, item.getId());
        SagaInstance claimSaga = em.getEntityManager()
                .createQuery("select s from SagaInstance s where s.orderClaim is not null", SagaInstance.class)
                .getSingleResult();
        OutboxEvent outbox = em.getEntityManager()
                .createQuery("select o from OutboxEvent o", OutboxEvent.class).getSingleResult();

        // then
        assertThat(foundOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(foundOrder.getVersion()).isZero();
        assertThat(foundOrder.getCreatedAt()).isNotNull();
        assertThat(foundItem.getStatus()).isEqualTo(OrderItemStatus.ORDERED);
        assertThat(foundItem.getOrder().getId()).isEqualTo(order.getId());
        assertThat(claimSaga.getCurrentStep()).isEqualTo(SagaStep.PAYMENT_CANCEL_REQUESTED);
        assertThat(claimSaga.getStatus()).isEqualTo(SagaStatus.STARTED);
        assertThat(claimSaga.getAttemptCount()).isZero();
        assertThat(claimSaga.getNextRetryAt()).isNull();
        assertThat(claimSaga.getOrder().getId()).isEqualTo(order.getId());
        assertThat(outbox.getStatus()).isEqualTo(OutboxEvent.Status.NEW);
        assertThat(outbox.getPayload()).contains("\"orderNo\"");
    }
}
