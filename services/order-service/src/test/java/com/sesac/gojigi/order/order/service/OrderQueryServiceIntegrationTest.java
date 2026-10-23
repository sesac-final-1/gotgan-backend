package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.dto.response.OrderDetailResponse;
import com.sesac.gojigi.order.order.dto.response.OrderItemResponse;
import com.sesac.gojigi.order.order.dto.response.OrderListResponse;
import com.sesac.gojigi.order.order.dto.response.OrderSummaryResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 내 주문 목록 · 상세 — 본인 주문만 · 최신순 · 상태별 개수 · 결제수단 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, OrderQueryService.class,
        OrderQueryServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class OrderQueryServiceIntegrationTest {

    /** 다른 테스트가 같은 DB 에 남긴 주문과 섞이지 않게 이 테스트만의 회원 ID */
    private static final long BUYER = 401L;
    private static final long OTHER = 402L;
    /** 목록 테스트 전용 — 상세 테스트가 BUYER 로 만든 주문과 섞이지 않게 */
    private static final long LISTER = 403L;

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private OrderQueryService orderQuery;
    @MockitoBean private PaymentClientAdapter payment;

    /** 상품주문 2건(스토어 2곳) 주문 — usePoint 를 다 쓰면 0원 주문 */
    private Order create(long buyerId, long usePoint) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(11L, 2)), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞", usePoint);
        OrderPricing.Result priced = OrderPricing.calculate(
                List.of(OrderPricingTest.line(11L, 1L, 15_000L, 2), OrderPricingTest.line(12L, 2L, 5_000L, 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), usePoint);
        return orderTx.createPending(new UserSummaryResponse(buyerId, "buyer@example.com", "홍길동", null), request,
                priced, Map.of(1L, "곳간상회", 2L, "들녘마켓")).order();
    }

    @Test
    @DisplayName("내 주문 목록 — 본인 주문만 최신순, 결제 전 · 실패 주문의 상품주문은 ORDERED 로 센다")
    void getMyOrders() {
        // given
        Order paid = create(LISTER, 1_000L);
        orderTx.markPaid(paid.getId());
        Order failed = create(LISTER, 0L);
        orderTx.markFailed(failed.getId());
        Order pending = create(LISTER, 0L);
        create(OTHER, 0L);

        // when
        OrderListResponse result = orderQuery.getMyOrders(LISTER);

        // then
        assertThat(result.orders()).extracting(OrderSummaryResponse::orderNo)
                .containsExactly(pending.getOrderNo(), failed.getOrderNo(), paid.getOrderNo());
        assertThat(result.orders()).extracting(OrderSummaryResponse::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.FAILED, OrderStatus.PAID);
        assertThat(result.orders().get(2).items()).extracting(OrderItemResponse::storeName)
                .containsExactly("곳간상회", "들녘마켓");
        assertThat(result.statusCounts()).hasSize(OrderItemStatus.values().length)
                .containsEntry(OrderItemStatus.PAYMENT_DONE, 2L)
                .containsEntry(OrderItemStatus.ORDERED, 4L)
                .containsEntry(OrderItemStatus.SHIPPING, 0L);
    }

    @Test
    @DisplayName("주문이 없으면 빈 목록 · 모든 상태 0")
    void getMyOrders_empty() {
        OrderListResponse result = orderQuery.getMyOrders(499L);

        assertThat(result.orders()).isEmpty();
        assertThat(result.statusCounts().values()).containsOnly(0L);
    }

    @Test
    @DisplayName("내 주문 상세 — 배송지 · 금액 · 적립 합계 · 결제수단, 다른 회원의 주문번호는 404")
    void getMyOrder() {
        // given
        Order order = create(BUYER, 1_000L);
        orderTx.markPaid(order.getId());
        given(payment.find(order.getOrderNo())).willReturn(
                new PaymentResponse(order.getOrderNo(), "pay-1", "PAID", order.getPayAmt(), 0L, "CARD", null));

        // when
        OrderDetailResponse detail = orderQuery.getMyOrder(BUYER, order.getOrderNo());

        // then
        assertThat(detail.status()).isEqualTo(OrderStatus.PAID);
        assertThat(detail.address1()).isEqualTo("서울 강남구 테헤란로 1");
        assertThat(detail.payAmt()).isEqualTo(35_000L + 6_000L - 1_000L);
        assertThat(detail.earnedPoint()).isEqualTo(detail.items().stream().mapToLong(OrderItemResponse::earnedPoint).sum())
                .isPositive();
        assertThat(detail.payment().method()).isEqualTo("CARD");
        assertThat(detail.items()).hasSize(2);
        assertThatThrownBy(() -> orderQuery.getMyOrder(OTHER, order.getOrderNo()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("0원(전액 포인트) 주문은 payment 를 부르지 않고 payment null")
    void getMyOrder_zeroPayAmount() {
        Order order = create(BUYER, 41_000L);

        OrderDetailResponse detail = orderQuery.getMyOrder(BUYER, order.getOrderNo());

        assertThat(detail.payAmt()).isZero();
        assertThat(detail.payment()).isNull();
        verifyNoInteractions(payment);
    }
}
