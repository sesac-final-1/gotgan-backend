package com.sesac.gojigi.order.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.repository.OrderItemHistoryRepository;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 반품 · 교환 신청 — 배송완료 7일 이내 · 환불 예정액 · 상품주문 상태 · 이력 · 거절 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, ClaimTxService.class,
        ClaimTxServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class ClaimTxServiceIntegrationTest {

    private static final AtomicLong BUYERS = new AtomicLong(651L);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"), ZoneId.of("Asia/Seoul"));   // 2026-10-26 12:00 KST
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private ClaimTxService claimTx;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderItemHistoryRepository historyRepository;
    @Autowired private OrderClaimRepository claimRepository;
    @Autowired private JdbcTemplate jdbc;

    /** 결제 완료 주문 — 상품주문 20,000원 1건(스토어 951) · 포인트 2,000 사용 */
    private OrderItem paidItem(long buyerId) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(95L, 1)), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, 2_000L);
        OrderPricing.Result priced = OrderPricing.calculate(List.of(new OrderPricing.Line(new ProductOptionResponse(95L,
                        95L, "상품 95", null, 951L, 301L, 3L, 20_000L, 0L, 20_000L, 100, true, null), 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 2_000L);
        Order order = orderTx.createPending(new UserSummaryResponse(buyerId, "buyer@example.com", "홍길동", null),
                request, priced, Map.of(951L, "곳간상회")).order();
        orderTx.markPaid(order.getId());
        return orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).get(0);
    }

    /** 배송 전이 메서드가 아직 없어 SQL 로 — deliveredAt 에 배송완료 */
    private void delivered(OrderItem item, LocalDateTime deliveredAt) {
        jdbc.update("update order_item set status = 'DELIVERED', delivered_at = ? where order_item_id = ?",
                deliveredAt, item.getId());
    }

    private static String orderNo(OrderItem item) {
        return item.getItemNo().substring(0, item.getItemNo().lastIndexOf('-'));
    }

    private ClaimResponse open(long buyerId, OrderItem item, ClaimType type, ClaimReasonCode reason, long fee) {
        return claimTx.openReturnOrExchange(buyerId, orderNo(item),
                new ClaimCreateRequest(item.getId(), type, reason, "상세"), fee);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("반품 신청 — 클레임 REQUESTED · 환불 예정액(실결제 배분액 - 반품 배송비) · 상품주문 RETURN_REQUESTED · 이력")
    void openReturn() {
        // given
        long buyer = BUYERS.getAndIncrement();
        OrderItem item = paidItem(buyer);
        delivered(item, NOW.minusDays(6));

        // when
        ClaimResponse response = open(buyer, item, ClaimType.RETURN, ClaimReasonCode.CHANGE_OF_MIND, 6_000L);

        // then — 20,000 - 포인트 2,000 - 반품 배송비 6,000
        assertThat(response.status()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(response.refundAmt()).isEqualTo(12_000L);
        assertThat(response.restorePoint()).isEqualTo(2_000L);
        OrderClaim claim = claimRepository.findById(response.claimId()).orElseThrow();
        assertThat(claim.getClaimType()).isEqualTo(ClaimType.RETURN);
        assertThat(claim.getRefundAmt()).isEqualTo(12_000L);
        assertThat(orderItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderItemStatus.RETURN_REQUESTED);
        assertThat(historyRepository.findByOrderItemIdOrderByIdAsc(item.getId()))
                .extracting(OrderItemHistory::getFromStatus, OrderItemHistory::getToStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(OrderItemStatus.DELIVERED,
                        OrderItemStatus.RETURN_REQUESTED));
    }

    @Test
    @DisplayName("교환 신청 — 금액 없음 · 상품주문 EXCHANGE_REQUESTED")
    void openExchange() {
        long buyer = BUYERS.getAndIncrement();
        OrderItem item = paidItem(buyer);
        delivered(item, NOW.minusDays(1));

        ClaimResponse response = open(buyer, item, ClaimType.EXCHANGE, ClaimReasonCode.WRONG_DELIVERY, 0L);

        assertThat(response.refundAmt()).isZero();
        assertThat(claimRepository.findById(response.claimId()).orElseThrow().getRefundAmt()).isNull();
        assertThat(orderItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderItemStatus.EXCHANGE_REQUESTED);
    }

    @Test
    @DisplayName("거절 — 배송완료 전 · 7일 지남은 CLAIM_NOT_RETURNABLE, 진행 중이면 CLAIM_IN_PROGRESS, 결제완료 상품주문 반품도 거절")
    void rejects() {
        // given
        long buyer = BUYERS.getAndIncrement();
        OrderItem notDelivered = paidItem(buyer);
        OrderItem expired = paidItem(buyer);
        delivered(expired, NOW.minusDays(7).minusMinutes(1));
        OrderItem requested = paidItem(buyer);
        delivered(requested, NOW.minusDays(1));
        open(buyer, requested, ClaimType.RETURN, ClaimReasonCode.DEFECTIVE, 0L);

        // when · then
        assertCode(() -> open(buyer, notDelivered, ClaimType.RETURN, ClaimReasonCode.DEFECTIVE, 0L),
                ClaimErrorCode.CLAIM_NOT_RETURNABLE);
        assertCode(() -> claimTx.find(buyer, orderNo(expired), expired.getId(), ClaimType.EXCHANGE),
                ClaimErrorCode.CLAIM_NOT_RETURNABLE);
        assertCode(() -> open(buyer, requested, ClaimType.EXCHANGE, ClaimReasonCode.DEFECTIVE, 0L),
                ClaimErrorCode.CLAIM_IN_PROGRESS);
        assertCode(() -> claimTx.find(buyer, orderNo(expired), expired.getId(), ClaimType.CANCEL),
                ClaimErrorCode.CLAIM_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("미리보기용 조회는 잠그지 않고, 잠금 조회는 트랜잭션 안에서만")
    void findAndLock() {
        long buyer = BUYERS.getAndIncrement();
        OrderItem item = paidItem(buyer);

        ClaimTxService.ClaimTarget target = claimTx.find(buyer, orderNo(item), item.getId(), ClaimType.CANCEL);

        assertThat(target.item().getId()).isEqualTo(item.getId());
        assertThat(target.cashLeft()).isEqualTo(20_000L + 3_000L - 2_000L);
        assertThat(target.storeShippingFeeIfLast()).isEqualTo(3_000L);
        assertThatThrownBy(() -> claimTx.lock(buyer, orderNo(item), item.getId(), ClaimType.CANCEL))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
