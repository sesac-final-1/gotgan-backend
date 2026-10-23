package com.sesac.gojigi.order.stats.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.stats.dto.response.PlatformOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.StoreOrderStatsResponse;
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

/** 스토어 · 전체 주문 통계 — 상태별 건수 · 진행 중 클레임 · 오늘 매출 · 거래액 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, OrderStatsService.class,
        OrderStatsServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class OrderStatsServiceIntegrationTest {

    /** 다른 테스트가 같은 DB 에 남긴 상품주문과 섞이지 않게 이 테스트만의 스토어 ID */
    private static final long STORE = 701L;
    private static final long OTHER_STORE = 702L;
    /** 전체 통계 테스트 전용 — 스토어 통계 테스트의 STORE 건수에 섞이지 않게 */
    private static final long PLATFORM_STORE = 711L;

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));   // 2026-10-22 12:00 KST
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private OrderStatsService stats;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderClaimRepository orderClaimRepository;
    @Autowired private JdbcTemplate jdbc;

    private static OrderPricing.Line line(long optionId, long storeId, long unitPrice, int quantity) {
        return new OrderPricing.Line(new ProductOptionResponse(optionId, optionId, "상품 " + optionId, null, storeId,
                301L, 3L, unitPrice, 0L, unitPrice, 100, true, null), quantity);
    }

    /** STORE 상품주문 20,000원 + OTHER_STORE 상품주문 5,000원 주문 — paid 면 결제 완료 */
    private Order order(boolean paid) {
        return order(STORE, paid);
    }

    /** store 상품주문 20,000원(첫 상품주문) + OTHER_STORE 상품주문 5,000원 */
    private Order order(long store, boolean paid) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(71L, 2)), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, 0L);
        OrderPricing.Result priced = OrderPricing.calculate(
                List.of(line(71L, store, 10_000L, 2), line(72L, OTHER_STORE, 5_000L, 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 0L);
        Order order = orderTx.createPending(new UserSummaryResponse(4L, "buyer@example.com", "홍길동", null), request,
                priced, Map.of(store, "곳간상회", OTHER_STORE, "들녘마켓")).order();
        if (paid) {
            orderTx.markPaid(order.getId());
        }
        return order;
    }

    /** 이 주문의 STORE 상품주문 */
    private OrderItem storeItem(Order order) {
        return orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).get(0);
    }

    /** 발주확인 · 송장 · 취소 전이 메서드가 아직 없어 상태는 SQL 로 바꾼다 */
    private void setStatus(OrderItem item, OrderItemStatus status) {
        jdbc.update("update order_item set status = ? where order_item_id = ?", status.name(), item.getId());
    }

    private void claim(OrderItem item, boolean completed) {
        OrderClaim claim = orderClaimRepository.save(OrderClaim.builder().orderItem(item).requesterId(4L)
                .claimType(ClaimType.CANCEL).reasonCode(ClaimReasonCode.CHANGE_OF_MIND)
                .requestedAt(LocalDateTime.of(2026, 10, 22, 11, 0)).build());
        if (completed) {
            jdbc.update("update order_claim set status = 'COMPLETED' where order_claim_id = ?", claim.getId());
        }
    }

    @Test
    @DisplayName("스토어 통계 — 발주확인 · 송장 대기 · 진행 중 클레임 건수와 합, 오늘 결제된 매출(결제 전 · 취소 · 어제 제외)")
    void getStoreStats() {
        // given
        order(true);                                   // PAYMENT_DONE · 오늘 매출 20,000
        Order toShip = order(true);                    // PREPARING · 오늘 매출 20,000 · 진행 중 클레임 1
        setStatus(storeItem(toShip), OrderItemStatus.PREPARING);
        claim(storeItem(toShip), false);
        Order cancelled = order(true);                 // CANCELLED · 매출 제외 · 끝난 클레임은 세지 않음
        setStatus(storeItem(cancelled), OrderItemStatus.CANCELLED);
        claim(storeItem(cancelled), true);
        Order yesterday = order(true);                 // PAYMENT_DONE 이지만 어제 결제 → 건수만
        jdbc.update("update orders set paid_at = ? where order_id = ?", LocalDateTime.of(2026, 10, 21, 23, 59),
                yesterday.getId());
        order(false);                                  // 결제 전(ORDERED) — 어디에도 안 셈

        // when
        StoreOrderStatsResponse result = stats.getStoreStats(STORE);

        // then
        assertThat(result.storeId()).isEqualTo(STORE);
        assertThat(result.toConfirmCount()).isEqualTo(2L);
        assertThat(result.toShipCount()).isEqualTo(1L);
        assertThat(result.claimCount()).isEqualTo(1L);
        assertThat(result.inProgressCount()).isEqualTo(4L);
        assertThat(result.todaySalesAmt()).isEqualTo(40_000L);
    }

    @Test
    @DisplayName("주문이 없는 스토어는 모두 0")
    void getStoreStats_empty() {
        StoreOrderStatsResponse result = stats.getStoreStats(799L);

        assertThat(result.inProgressCount()).isZero();
        assertThat(result.todaySalesAmt()).isZero();
    }

    @Test
    @DisplayName("전체 통계 — 결제된 주문만 주문 수 · 거래액에 더해진다 (결제 전 · 실패 · 취소된 상품주문 제외)")
    void getPlatformStats() {
        // given
        PlatformOrderStatsResponse before = stats.getPlatformStats();
        order(PLATFORM_STORE, true);                   // +1건 · +25,000
        Order cancelledItem = order(PLATFORM_STORE, true);   // +1건 · 첫 상품주문 취소라 +5,000
        setStatus(storeItem(cancelledItem), OrderItemStatus.CANCELLED);
        order(PLATFORM_STORE, false);                  // 결제 전 — 안 셈
        orderTx.markFailed(order(PLATFORM_STORE, false).getId());   // 실패 — 안 셈

        // when
        PlatformOrderStatsResponse after = stats.getPlatformStats();

        // then
        assertThat(after.orderCount() - before.orderCount()).isEqualTo(2L);
        assertThat(after.totalTradeAmt() - before.totalTradeAmt()).isEqualTo(25_000L + 5_000L);
    }
}
