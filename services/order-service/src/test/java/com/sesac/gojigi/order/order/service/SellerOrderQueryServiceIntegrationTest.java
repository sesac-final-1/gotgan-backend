package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.dto.response.SellerOrderItemListResponse;
import com.sesac.gojigi.order.order.dto.response.SellerOrderItemResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 주문 목록 · 상세 — 내 스토어 · 결제 후만, 상태 탭 · 검색 · 페이징 · 탭 숫자 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, SellerOrderQueryService.class,
        SellerOrderQueryServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class SellerOrderQueryServiceIntegrationTest {

    /** 이 테스트만의 스토어 — 다른 테스트의 상품주문과 섞이지 않게 (목록 테스트 · 상세 테스트 따로) */
    private static final long STORE = 981L;
    private static final long DETAIL_STORE = 982L;
    private static final long OTHER_STORE = 989L;

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private SellerOrderQueryService query;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private JdbcTemplate jdbc;

    /** store 상품주문 1건 + OTHER_STORE 상품주문 1건 주문 — paid 면 결제 완료. store 상품주문을 돌려준다 */
    private OrderItem item(long store, String productName, String receiver, boolean paid) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(98L, 1)), receiver,
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", "문 앞", 0L);
        OrderPricing.Result priced = OrderPricing.calculate(List.of(
                        new OrderPricing.Line(new ProductOptionResponse(98L, 98L, productName, "특", store, 301L, 3L,
                                20_000L, 0L, 20_000L, 100, true, "https://img/98.jpg"), 2),
                        new OrderPricing.Line(new ProductOptionResponse(99L, 99L, "남의 상품", null, OTHER_STORE, 301L, 3L,
                                5_000L, 0L, 5_000L, 100, true, null), 1)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), 0L);
        Order order = orderTx.createPending(new UserSummaryResponse(4L, "buyer@example.com", "홍길동", "곳간지기"),
                request, priced, Map.of(store, "곳간상회", OTHER_STORE, "들녘마켓")).order();
        if (paid) {
            orderTx.markPaid(order.getId());
        }
        return orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).get(0);
    }

    private void setStatus(OrderItem item, OrderItemStatus status, String trackingNo) {
        jdbc.update("update order_item set status = ?, tracking_no = ? where order_item_id = ?", status.name(),
                trackingNo, item.getId());
    }

    @Test
    @DisplayName("목록 — 내 스토어 · 결제 후만 최신 순, 상태 탭 · 검색 · 페이징, 탭 숫자는 전체 기준")
    void list() {
        // given
        OrderItem first = item(STORE, "유기농 사과", "김수령", true);           // PAYMENT_DONE
        OrderItem second = item(STORE, "제주 감귤", "이수령", true);
        setStatus(second, OrderItemStatus.SHIPPING, "651200009999");
        OrderItem third = item(STORE, "유기농 배", "박수령", true);
        item(STORE, "결제 전 상품", "최수령", false);                         // ORDERED — 보이지 않음

        // when
        SellerOrderItemListResponse all = query.list(STORE, null, null, 0, 20);
        SellerOrderItemListResponse paged = query.list(STORE, null, "  ", 1, 2);
        SellerOrderItemListResponse shipping = query.list(STORE, OrderItemStatus.SHIPPING, null, 0, 20);

        // then
        assertThat(all.items().content()).extracting(SellerOrderItemResponse::orderItemId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(all.items().totalElements()).isEqualTo(3);
        assertThat(paged.items().content()).extracting(SellerOrderItemResponse::orderItemId).containsExactly(first.getId());
        assertThat(paged.items().totalPages()).isEqualTo(2);
        assertThat(shipping.items().content()).extracting(SellerOrderItemResponse::trackingNo).containsExactly("651200009999");
        assertThat(all.statusCounts()).containsEntry(OrderItemStatus.PAYMENT_DONE, 2L)
                .containsEntry(OrderItemStatus.SHIPPING, 1L).containsEntry(OrderItemStatus.CONFIRMED, 0L)
                .doesNotContainKey(OrderItemStatus.ORDERED);
        assertThat(query.list(STORE, OrderItemStatus.ORDERED, null, 0, 20).items().content()).isEmpty();

        // 검색 — 상품명 · 수령인 · 구매자(공개용 이름) · 송장번호 · 상품주문번호
        assertThat(query.list(STORE, null, "유기농", 0, 20).items().totalElements()).isEqualTo(2);
        assertThat(query.list(STORE, null, "이수령", 0, 20).items().content()).extracting(SellerOrderItemResponse::orderItemId)
                .containsExactly(second.getId());
        assertThat(query.list(STORE, null, "9999", 0, 20).items().totalElements()).isEqualTo(1);
        assertThat(query.list(STORE, null, third.getItemNo(), 0, 20).items().totalElements()).isEqualTo(1);
        assertThat(query.list(STORE, null, "곳간지기", 0, 20).items().totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("상세 — 스냅샷 · 배송지 · 공개용 구매자 이름, 남의 스토어 · 결제 전 · 없는 상품주문은 404, 스토어 없으면 403")
    void get() {
        // given
        OrderItem paid = item(DETAIL_STORE, "유기농 사과", "김수령", true);
        OrderItem unpaid = item(DETAIL_STORE, "결제 전", "김수령", false);

        // when
        SellerOrderItemResponse found = query.get(DETAIL_STORE, paid.getId());

        // then
        assertThat(found.itemNo()).isEqualTo(paid.getItemNo());
        assertThat(found.orderNo()).isEqualTo(found.itemNo().substring(0, found.itemNo().lastIndexOf('-')));
        assertThat(found.status()).isEqualTo(OrderItemStatus.PAYMENT_DONE);
        assertThat(found.productName()).isEqualTo("유기농 사과");
        assertThat(found.itemAmt()).isEqualTo(40_000L);
        assertThat(found.buyerName()).isEqualTo("곳간지기");
        assertThat(found.receiverName()).isEqualTo("김수령");
        assertThat(found.address2()).isEqualTo("101호");
        assertThat(found.paidAt()).isNotNull();
        assertNotFound(OTHER_STORE, paid.getId());
        assertNotFound(DETAIL_STORE, unpaid.getId());
        assertNotFound(DETAIL_STORE, Long.MAX_VALUE);
        assertThatThrownBy(() -> query.get(null, paid.getId())).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> query.list(null, null, null, 0, 20)).isInstanceOf(BusinessException.class);
    }

    private void assertNotFound(long storeId, long orderItemId) {
        assertThatThrownBy(() -> query.get(storeId, orderItemId)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
    }
}
