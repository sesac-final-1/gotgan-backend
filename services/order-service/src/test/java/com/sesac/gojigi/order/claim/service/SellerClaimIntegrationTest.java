package com.sesac.gojigi.order.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.request.ClaimStage;
import com.sesac.gojigi.order.claim.dto.response.ClaimDetailResponse;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.dto.response.SellerClaimListResponse;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.common.config.SchedulingConfig;
import com.sesac.gojigi.order.common.event.EventOutboxService;
import com.sesac.gojigi.order.common.event.SnsEventPublisher;
import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.UserClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.service.DeliveryTxService;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.saga.entity.SagaStatus;
import com.sesac.gojigi.order.saga.entity.SagaType;
import com.sesac.gojigi.order.saga.repository.SagaInstanceRepository;
import com.sesac.gojigi.order.saga.service.CancelItemTxService;
import com.sesac.gojigi.order.saga.service.CancelOrderItemSaga;
import com.sesac.gojigi.order.saga.service.OrderSagaTxService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판매자 클레임 처리 (CLM-03 ~ 05) — 반품: 승인 → 수거 완료 → 환불 Saga → 반품완료 / 교환: 승인 → 수거 → 재배송 송장 → 배송완료로 교환완료,
 * 거부 · 목록 탭 숫자 · 내 클레임 · 잘못된 전이 (MySQL 8.4 + Flyway, 원격 참여자는 목)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, ClaimTxService.class, SellerClaimTxService.class,
        ClaimQueryService.class, CancelItemTxService.class, CancelOrderItemSaga.class, OrderSagaTxService.class,
        DeliveryTxService.class, EventOutboxService.class, SchedulingConfig.class, SellerClaimIntegrationTest.Beans.class })
@ActiveProfiles("test")
@TestPropertySource(properties = "app.order.auto-confirm-after=7d")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class SellerClaimIntegrationTest {

    private static final AtomicLong BUYERS = new AtomicLong(1_101L);
    /** 이 테스트만의 스토어 — 테스트마다 따로 (목록 탭 숫자가 섞이지 않게) */
    private static final long STORE = 1_101L;
    private static final long LIST_STORE = 1_102L;
    private static final long SELLER = 70L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneId.of("Asia/Seoul"));   // 2026-10-27 12:00 KST
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private ClaimTxService claimTx;
    @Autowired private SellerClaimTxService sellerClaimTx;
    @Autowired private ClaimQueryService claimQuery;
    @Autowired private CancelOrderItemSaga cancelSaga;
    @Autowired private DeliveryTxService deliveryTx;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderClaimRepository claimRepository;
    @Autowired private SagaInstanceRepository sagaRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private SnsEventPublisher sns;
    @MockitoBean private PaymentClientAdapter payment;
    @MockitoBean private ProductClientAdapter product;
    @MockitoBean private UserClientAdapter user;

    /** 배송완료(하루 전) 상품주문 — 20,000원 · 포인트 2,000 사용 */
    private OrderItem deliveredItem(long buyer, long store) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(111L, 1)), "김수령",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, 2_000L);
        OrderPricing.Result priced = OrderPricing.calculate(List.of(new OrderPricing.Line(new ProductOptionResponse(111L,
                        111L, "상품 111", "특", store, 301L, 3L, 20_000L, 0L, 20_000L, 100, true, null), 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 2_000L);
        Order order = orderTx.createPending(new UserSummaryResponse(buyer, "buyer@example.com", "홍길동", "곳간지기"),
                request, priced, Map.of(store, "곳간상회")).order();
        orderTx.markPaid(order.getId());
        OrderItem item = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).get(0);
        jdbc.update("update order_item set status = 'DELIVERED', shipped_at = ?, delivered_at = ?, courier = 'CJ대한통운', "
                + "tracking_no = '651200000001' where order_item_id = ?", NOW.minusDays(2), NOW.minusDays(1), item.getId());
        return item;
    }

    private ClaimResponse open(long buyer, OrderItem item, ClaimType type, ClaimReasonCode reason) {
        String orderNo = item.getItemNo().substring(0, item.getItemNo().lastIndexOf('-'));
        return claimTx.openReturnOrExchange(buyer, orderNo, new ClaimCreateRequest(item.getId(), type, reason, "상세"),
                type == ClaimType.RETURN && reason == ClaimReasonCode.CHANGE_OF_MIND ? 6_000L : 0L);
    }

    private OrderItem reload(OrderItem item) {
        return orderItemRepository.findById(item.getId()).orElseThrow();
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("반품 — 승인(수거 요청) → 수거 완료 → 결제 부분 취소 · 재고 복원 · 포인트 환원으로 반품완료, 막히면 COLLECTED 로 남는다")
    void returnFlow() {
        // given
        long buyer = BUYERS.getAndIncrement();
        OrderItem item = deliveredItem(buyer, STORE);
        ClaimResponse claim = open(buyer, item, ClaimType.RETURN, ClaimReasonCode.CHANGE_OF_MIND);

        // when — 승인 · 수거 완료
        ClaimDetailResponse approved = sellerClaimTx.approve(STORE, claim.claimId());
        ClaimStatus done = cancelSaga.collectReturn(STORE, claim.claimId());

        // then — 20,000 - 포인트 2,000 - 반품 배송비 6,000 = 12,000 환불
        assertThat(approved.status()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(done).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(reload(item).getStatus()).isEqualTo(OrderItemStatus.RETURNED);
        assertThat(claimRepository.findById(claim.claimId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.COMPLETED);
        verify(payment).cancel(anyString(), eq(PaymentCancelRequest.partial(sagaIdOf(claim.claimId()), item.getId(),
                12_000L, "반품 환불")));
        verify(user).reversePoints(any());
        assertThat(sagaRepository.findAll()).filteredOn(s -> s.getOrderClaim() != null
                        && s.getOrderClaim().getId().equals(claim.claimId()))
                .singleElement().satisfies(s -> {
                    assertThat(s.getSagaType()).isEqualTo(SagaType.RETURN_ITEM);
                    assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPLETED);
                });

        // 결제 취소가 막히면 — 클레임 COLLECTED · 상품주문 반품요청 그대로 (복구 스케줄러가 마저)
        long buyer2 = BUYERS.getAndIncrement();
        OrderItem stuck = deliveredItem(buyer2, STORE);
        ClaimResponse stuckClaim = open(buyer2, stuck, ClaimType.RETURN, ClaimReasonCode.DEFECTIVE);
        sellerClaimTx.approve(STORE, stuckClaim.claimId());
        willThrow(new IllegalStateException("payment down")).given(payment).cancel(anyString(), any());
        assertThat(cancelSaga.collectReturn(STORE, stuckClaim.claimId())).isEqualTo(ClaimStatus.COLLECTED);
        assertThat(reload(stuck).getStatus()).isEqualTo(OrderItemStatus.RETURN_REQUESTED);
    }

    private String sagaIdOf(Long claimId) {
        return sagaRepository.findAll().stream().filter(s -> s.getOrderClaim() != null
                && s.getOrderClaim().getId().equals(claimId)).findFirst().orElseThrow().getSagaId();
    }

    @Test
    @DisplayName("교환 — 승인 → 수거 → 재배송 송장(상품주문 송장 갱신) → 배송완료 버튼 · 자동 배송완료 대상으로 교환완료, 상품주문 다시 배송완료")
    void exchangeFlow() {
        // given
        long buyer = BUYERS.getAndIncrement();
        OrderItem item = deliveredItem(buyer, STORE);
        ClaimResponse claim = open(buyer, item, ClaimType.EXCHANGE, ClaimReasonCode.WRONG_DELIVERY);

        // when
        sellerClaimTx.approve(STORE, claim.claimId());
        assertThat(sellerClaimTx.collectExchange(STORE, claim.claimId()).status()).isEqualTo(ClaimStatus.COLLECTED);
        ClaimDetailResponse reshipped = sellerClaimTx.reship(STORE, SELLER, claim.claimId(), "롯데택배", null);

        // then — 재배송 송장은 mock(택배사 접두 + 상품주문 ID), 상품주문은 교환요청 그대로 · 자동 배송완료 대상
        assertThat(reshipped.status()).isEqualTo(ClaimStatus.RESHIPPED);
        assertThat(reshipped.trackingNo()).isEqualTo(String.format("2470%08d", item.getId()));
        assertThat(reload(item).getStatus()).isEqualTo(OrderItemStatus.EXCHANGE_REQUESTED);
        assertThat(deliveryTx.findShippedBefore(NOW.plusSeconds(1), 100)).contains(item.getId());

        // 재배송 배송완료 (판매자 버튼) → 교환완료
        deliveryTx.deliver(STORE, SELLER, item.getId());
        OrderItem delivered = reload(item);
        assertThat(delivered.getStatus()).isEqualTo(OrderItemStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isEqualTo(NOW);
        assertThat(claimRepository.findById(claim.claimId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(deliveryTx.findShippedBefore(NOW.plusSeconds(1), 100)).doesNotContain(item.getId());
    }

    @Test
    @DisplayName("거부 — 사유 저장 · 상품주문 다시 배송완료, 잘못된 전이 409 · 남의 스토어 404 · 스토어 없음 403")
    void rejectAndInvalid() {
        // given
        long buyer = BUYERS.getAndIncrement();
        OrderItem item = deliveredItem(buyer, STORE);
        ClaimResponse claim = open(buyer, item, ClaimType.RETURN, ClaimReasonCode.CHANGE_OF_MIND);
        long buyer2 = BUYERS.getAndIncrement();
        OrderItem exchangeItem = deliveredItem(buyer2, STORE);
        ClaimResponse exchange = open(buyer2, exchangeItem, ClaimType.EXCHANGE, ClaimReasonCode.CHANGE_OF_MIND);

        // when
        ClaimDetailResponse rejected = sellerClaimTx.reject(STORE, SELLER, claim.claimId(), "사용 흔적이 있어요");

        // then
        assertThat(rejected.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(rejected.rejectReason()).isEqualTo("사용 흔적이 있어요");
        assertThat(reload(item).getStatus()).isEqualTo(OrderItemStatus.DELIVERED);
        assertCode(() -> sellerClaimTx.approve(STORE, claim.claimId()), ClaimErrorCode.CLAIM_INVALID_STATUS);
        assertCode(() -> sellerClaimTx.collectExchange(STORE, exchange.claimId()), ClaimErrorCode.CLAIM_INVALID_STATUS);   // 승인 전
        assertCode(() -> cancelSaga.collectReturn(STORE, exchange.claimId()), ClaimErrorCode.CLAIM_INVALID_STATUS);     // 교환은 환불 없음
        assertCode(() -> sellerClaimTx.reship(STORE, SELLER, exchange.claimId(), "CJ대한통운", null),
                ClaimErrorCode.CLAIM_INVALID_STATUS);
        sellerClaimTx.reject(STORE, SELLER, exchange.claimId(), "재고 없음");
        assertThat(reload(exchangeItem).getStatus()).isEqualTo(OrderItemStatus.DELIVERED);
        assertCode(() -> sellerClaimTx.approve(STORE + 9, claim.claimId()), ClaimErrorCode.CLAIM_NOT_FOUND);
        assertCode(() -> cancelSaga.collectReturn(STORE + 9, claim.claimId()), ClaimErrorCode.CLAIM_NOT_FOUND);
        assertCode(() -> sellerClaimTx.approve(null, claim.claimId()), CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("목록 — 처리대기 · 처리완료 · 유형 필터, 탭 숫자(단계는 유형 기준 · 유형은 단계 기준), 상세 · 내 클레임")
    void listAndMine() {
        // given — 처리대기 반품 1 · 교환 1, 처리완료(거부) 반품 1
        long buyer = BUYERS.getAndIncrement();
        ClaimResponse ret = open(buyer, deliveredItem(buyer, LIST_STORE), ClaimType.RETURN, ClaimReasonCode.DEFECTIVE);
        ClaimResponse exchange = open(buyer, deliveredItem(buyer, LIST_STORE), ClaimType.EXCHANGE, ClaimReasonCode.CHANGE_OF_MIND);
        ClaimResponse rejected = open(buyer, deliveredItem(buyer, LIST_STORE), ClaimType.RETURN, ClaimReasonCode.CHANGE_OF_MIND);
        sellerClaimTx.reject(LIST_STORE, SELLER, rejected.claimId(), "기간 지남");

        // when
        SellerClaimListResponse open = claimQuery.listSeller(LIST_STORE, ClaimStage.OPEN, null, 0, 20);
        SellerClaimListResponse doneReturns = claimQuery.listSeller(LIST_STORE, ClaimStage.DONE, ClaimType.RETURN, 0, 20);

        // then
        assertThat(open.items().content()).extracting(ClaimDetailResponse::claimId)
                .containsExactly(exchange.claimId(), ret.claimId());
        assertThat(open.stageCounts()).containsEntry(ClaimStage.OPEN, 2L).containsEntry(ClaimStage.DONE, 1L);
        assertThat(open.typeCounts()).containsEntry(ClaimType.RETURN, 1L).containsEntry(ClaimType.EXCHANGE, 1L)
                .containsEntry(ClaimType.CANCEL, 0L);
        assertThat(doneReturns.items().content()).extracting(ClaimDetailResponse::rejectReason).containsExactly("기간 지남");
        assertThat(doneReturns.stageCounts()).containsEntry(ClaimStage.OPEN, 1L).containsEntry(ClaimStage.DONE, 1L);
        ClaimDetailResponse detail = claimQuery.getSeller(LIST_STORE, ret.claimId());
        assertThat(detail.buyerName()).isEqualTo("곳간지기");
        assertThat(detail.refundAmt()).isEqualTo(18_000L);   // 판매자 귀책 — 반품 배송비 없음
        assertThat(claimQuery.getSeller(LIST_STORE, exchange.claimId()).refundAmt()).isZero();
        assertThat(claimQuery.listMine(buyer)).extracting(ClaimDetailResponse::claimId)
                .containsExactly(rejected.claimId(), exchange.claimId(), ret.claimId());
        assertCode(() -> claimQuery.getSeller(LIST_STORE + 9, ret.claimId()), ClaimErrorCode.CLAIM_NOT_FOUND);
        assertCode(() -> claimQuery.listSeller(null, ClaimStage.OPEN, null, 0, 20), CommonErrorCode.FORBIDDEN);
    }
}
