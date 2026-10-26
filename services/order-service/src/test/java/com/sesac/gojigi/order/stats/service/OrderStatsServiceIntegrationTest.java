package com.sesac.gojigi.order.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
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
import com.sesac.gojigi.order.stats.dto.response.DailyOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.PlatformOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.StoreOrderStatsResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/** 스토어 · 전체 주문 통계 — 상태별 건수 · 진행 중 상품주문 · 오늘 매출 · 거래액 (MySQL 8.4 + Flyway) */
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
    /** 일별 통계 테스트 전용 */
    private static final long DAILY_STORE = 721L;

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

    /** 결제된 주문의 STORE 상품주문을 status 로 바꾼 주문 */
    private Order paidWith(OrderItemStatus status) {
        Order order = order(true);
        setStatus(storeItem(order), status);
        return order;
    }

    private void claim(Order order, ClaimStatus status) {
        OrderClaim claim = orderClaimRepository.save(OrderClaim.builder().orderItem(storeItem(order)).requesterId(4L)
                .claimType(ClaimType.CANCEL).reasonCode(ClaimReasonCode.CHANGE_OF_MIND)
                .requestedAt(LocalDateTime.of(2026, 10, 22, 11, 0)).build());
        jdbc.update("update order_claim set status = ? where order_claim_id = ?", status.name(), claim.getId());
    }

    @Test
    @DisplayName("스토어 통계 — 진행 중은 결제 완료 ~ 배송 완료 + 클레임 진행 중(환불 미완료 취소 포함), 끝난 주문 · 결제 전은 빠진다")
    void getStoreStats() {
        // given — 진행 중 6건
        order(true);                                               // PAYMENT_DONE
        Order toShip = paidWith(OrderItemStatus.PREPARING);        // + 진행 중 클레임 (한 번만 센다)
        claim(toShip, ClaimStatus.REQUESTED);
        paidWith(OrderItemStatus.SHIPPING);
        paidWith(OrderItemStatus.DELIVERED);
        Order refunding = paidWith(OrderItemStatus.CANCELLED);    // 취소 완료지만 환불 진행 중(클레임 APPROVED)
        claim(refunding, ClaimStatus.APPROVED);
        Order yesterday = order(true);                             // PAYMENT_DONE · 어제 결제라 매출에서만 빠짐
        jdbc.update("update orders set paid_at = ? where order_id = ?", LocalDateTime.of(2026, 10, 21, 23, 59),
                yesterday.getId());
        // 끝났거나 결제 전 — 진행 중 아님
        paidWith(OrderItemStatus.CONFIRMED);                       // 매출에는 들어감
        Order cancelled = paidWith(OrderItemStatus.CANCELLED);
        claim(cancelled, ClaimStatus.COMPLETED);
        paidWith(OrderItemStatus.RETURNED);
        order(false);                                              // ORDERED

        // when
        StoreOrderStatsResponse result = stats.getStoreStats(STORE);

        // then
        assertThat(result.storeId()).isEqualTo(STORE);
        assertThat(result.toConfirmCount()).isEqualTo(2L);
        assertThat(result.toShipCount()).isEqualTo(1L);
        assertThat(result.claimCount()).isEqualTo(2L);
        assertThat(result.inProgressCount()).isEqualTo(6L);
        // 오늘 결제 · 취소 · 반품 완료 아님: PAYMENT_DONE · PREPARING · SHIPPING · DELIVERED · CONFIRMED
        assertThat(result.todaySalesAmt()).isEqualTo(5 * 20_000L);
    }

    @Test
    @DisplayName("주문이 없는 스토어는 모두 0")
    void getStoreStats_empty() {
        StoreOrderStatsResponse result = stats.getStoreStats(799L);

        assertThat(result.inProgressCount()).isZero();
        assertThat(result.todaySalesAmt()).isZero();
    }

    private void paidAt(Order order, LocalDateTime at) {
        jdbc.update("update orders set paid_at = ? where order_id = ?", at, order.getId());
    }

    @Test
    @DisplayName("일별 통계 — 최근 days 일을 결제일(KST)별로, 거래 없는 날은 0 · 취소 상품주문 제외 · 범위 밖 · 결제 전은 빠진다")
    void getDailyStats() {
        // given — 오늘 2026-10-22, days=3 → 10-20 ~ 10-22
        List<DailyOrderStatsResponse> before = stats.getDailyStats(null, 3);
        order(DAILY_STORE, true);                                         // 오늘 · 스토어 20,000 + 다른 스토어 5,000
        paidAt(order(DAILY_STORE, true), LocalDateTime.of(2026, 10, 21, 23, 59));   // 어제 끝
        Order cancelled = order(DAILY_STORE, true);                       // 오늘 · 스토어 상품주문 취소 → 다른 스토어 5,000 만
        setStatus(storeItem(cancelled), OrderItemStatus.CANCELLED);
        paidAt(order(DAILY_STORE, true), LocalDateTime.of(2026, 10, 19, 23, 59));   // 범위 밖
        order(DAILY_STORE, false);                                        // 결제 전

        // when
        List<DailyOrderStatsResponse> store = stats.getDailyStats(DAILY_STORE, 3);
        List<DailyOrderStatsResponse> after = stats.getDailyStats(null, 3);

        // then — 스토어: 그 스토어 매출 상품주문만, 주문 수도 그 상품주문이 든 주문만
        assertThat(store).extracting(DailyOrderStatsResponse::date, DailyOrderStatsResponse::tradeAmt,
                        DailyOrderStatsResponse::orderCount)
                .containsExactly(tuple(LocalDate.of(2026, 10, 20), 0L, 0L), tuple(LocalDate.of(2026, 10, 21), 20_000L, 1L),
                        tuple(LocalDate.of(2026, 10, 22), 20_000L, 1L));
        // 전체: 다른 테스트 데이터와 섞이므로 증가분 — 어제 1건 25,000, 오늘 2건 25,000 + 5,000
        assertThat(after).extracting(DailyOrderStatsResponse::date).isEqualTo(before.stream().map(DailyOrderStatsResponse::date).toList());
        assertThat(after.get(1).tradeAmt() - before.get(1).tradeAmt()).isEqualTo(25_000L);
        assertThat(after.get(1).orderCount() - before.get(1).orderCount()).isEqualTo(1L);
        assertThat(after.get(2).tradeAmt() - before.get(2).tradeAmt()).isEqualTo(30_000L);
        assertThat(after.get(2).orderCount() - before.get(2).orderCount()).isEqualTo(2L);
        assertThat(stats.getDailyStats(DAILY_STORE, 30)).hasSize(30).first()
                .extracting(DailyOrderStatsResponse::date).isEqualTo(LocalDate.of(2026, 9, 23));
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
