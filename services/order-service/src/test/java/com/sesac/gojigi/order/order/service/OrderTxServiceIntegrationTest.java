package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.entity.OrderStoreShipment;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import com.sesac.gojigi.order.order.repository.OrderStoreShipmentRepository;
import com.sesac.gojigi.order.order.service.OrderTxService.PendingOrder;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStatus;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.repository.SagaInstanceRepository;
import com.sesac.gojigi.order.saga.service.SagaStore;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 주문 PENDING 저장(주문번호 · 스냅샷 · 배송비) · 상태 변경 · Saga 기록 — MySQL 8.4 + Flyway */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, SagaStore.class,
        OrderTxServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋 (Saga 와 같은 조건)
class OrderTxServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), KST);   // 2026-10-22 12:00 KST
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private SagaStore sagaStore;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderStoreShipmentRepository shipmentRepository;
    @Autowired private SagaInstanceRepository sagaInstanceRepository;

    private PendingOrder create(String nick) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(11L, 2)), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞", 1_000L);
        OrderPricing.Result priced = OrderPricing.calculate(
                List.of(OrderPricingTest.line(11L, 1L, 15_000L, 2), OrderPricingTest.line(12L, 2L, 5_000L, 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 1_000L);
        return orderTx.createPending(new UserSummaryResponse(4L, "buyer@example.com", "홍길동", nick), request, priced,
                Map.of(1L, "곳간상회", 2L, "들녘마켓"));
    }

    @Test
    @DisplayName("주문 PENDING · 상품주문 스냅샷 · 스토어별 배송비를 저장하고, 주문번호는 그날 순번")
    void createPending_savesSnapshotAndSequence() {
        // when
        PendingOrder first = create(null);
        PendingOrder second = create("곳간지기");

        // then
        Order order = orderRepository.findById(first.order().getId()).orElseThrow();
        long firstSeq = Long.parseLong(first.order().getOrderNo().substring("20261022-".length()));
        assertThat(first.order().getOrderNo()).matches("20261022-\\d{6}");
        assertThat(second.order().getOrderNo()).isEqualTo(String.format("20261022-%06d", firstSeq + 1));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getOrdererName()).isEqualTo("홍길동");
        assertThat(order.getOrdererDisplayName()).isEqualTo("홍*동");
        assertThat(second.order().getOrdererDisplayName()).isEqualTo("곳간지기");
        assertThat(order.getTotalShippingFee()).isEqualTo(6_000L);
        assertThat(order.getPayAmt()).isEqualTo(35_000L + 6_000L - 1_000L);
        assertThat(first.items()).extracting(OrderItem::getItemNo)
                .containsExactly(first.order().getOrderNo() + "-01", first.order().getOrderNo() + "-02");
        assertThat(first.items()).extracting(OrderItem::getStoreName).containsExactly("곳간상회", "들녘마켓");
        assertThat(shipmentRepository.findAll()).filteredOn(s -> s.getOrder().getId().equals(order.getId()))
                .extracting(OrderStoreShipment::getStoreId, OrderStoreShipment::getShippingFee)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(1L, 3_000L),
                        org.assertj.core.groups.Tuple.tuple(2L, 3_000L));
    }

    @Test
    @DisplayName("결제 완료 · 실패 처리와 Saga 진행 · 보상 · 재시도 기록이 각각 커밋된다")
    void markPaidAndFailed_andSagaRecords() {
        // given
        Order paid = create(null).order();
        Order failed = create(null).order();
        SagaInstance saga = sagaStore.startPlaceOrder(failed);

        // when
        orderTx.markPaid(paid.getId());
        orderTx.markFailed(failed.getId());
        sagaStore.advance(saga.getId(), SagaStep.STOCK_RESERVED);
        sagaStore.startCompensating(saga.getId(), "ORDER_OUT_OF_STOCK");
        sagaStore.scheduleRetry(saga.getId(), "EXTERNAL_API_ERROR");

        // then
        Order paidFound = orderRepository.findById(paid.getId()).orElseThrow();
        assertThat(paidFound.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paidFound.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 0));
        assertThat(orderRepository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.FAILED);
        SagaInstance found = sagaInstanceRepository.findById(saga.getId()).orElseThrow();
        assertThat(found.getSagaId()).hasSize(36);
        assertThat(found.getCurrentStep()).isEqualTo(SagaStep.STOCK_RESERVED);
        assertThat(found.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(found.getNextRetryAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 1));

        sagaStore.compensated(saga.getId());
        SagaInstance done = sagaStore.startPlaceOrder(paid);
        sagaStore.complete(done.getId());
        assertThat(sagaInstanceRepository.findById(saga.getId()).orElseThrow().getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(sagaInstanceRepository.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("공개 표시명 — 닉네임 우선, 없으면 실명 가운데를 가린다")
    void displayName_masking() {
        assertThat(OrderTxService.displayName(new UserSummaryResponse(1L, "e", "홍길동", " "))).isEqualTo("홍*동");
        assertThat(OrderTxService.displayName(new UserSummaryResponse(1L, "e", "남궁민수", null))).isEqualTo("남**수");
        assertThat(OrderTxService.displayName(new UserSummaryResponse(1L, "e", "홍길", null))).isEqualTo("홍*");
        assertThat(OrderTxService.displayName(new UserSummaryResponse(1L, "e", "홍", null))).isEqualTo("*");
    }
}
