package com.sesac.gojigi.order.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.cart.dto.request.CartItemAddRequest;
import com.sesac.gojigi.order.cart.dto.response.CartItemsResponse;
import com.sesac.gojigi.order.cart.entity.CartItem;
import com.sesac.gojigi.order.cart.exception.CartErrorCode;
import com.sesac.gojigi.order.cart.repository.CartItemRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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

/** 장바구니 담기 · 삭제 · 결제 확정 시 비우기 + 커밋 후 Redis 갱신 — MySQL 8.4 + Flyway (Redis · product 는 목) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, CartService.class, OrderTxService.class,
        CartServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋 (AFTER_COMMIT Redis 갱신이 돌게)
class CartServiceIntegrationTest {

    private static final AtomicLong BUYERS = new AtomicLong(88_000L);   // 테스트끼리 장바구니가 섞이지 않게

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private CartService cartService;
    @Autowired private OrderTxService orderTx;
    @Autowired private CartItemRepository cartItemRepository;

    @MockitoBean private ProductClientAdapter productClient;
    @MockitoBean private CartRedisRepository cartRedisRepository;

    @Test
    @DisplayName("담기 — 새 옵션은 product 의 productId 로 저장, 담긴 옵션 · 요청 안 같은 옵션은 수량을 더하고 커밋 뒤 Redis 를 전체로 갱신")
    void add_insertsAndAccumulates() {
        // given
        long buyer = BUYERS.incrementAndGet();
        givenOptions(option(11L, 101L, true), option(12L, 102L, true));
        cartService.add(buyer, request(item(11L, 1)));

        // when
        CartItemsResponse response = cartService.add(buyer, request(item(11L, 2), item(12L, 1), item(12L, 3)));

        // then
        assertThat(response.cartCount()).isEqualTo(2);
        assertThat(response.items()).extracting(CartItemsResponse.Item::productOptionId, CartItemsResponse.Item::productId,
                        CartItemsResponse.Item::quantity, CartItemsResponse.Item::checked)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11L, 101L, 3, true),
                        org.assertj.core.groups.Tuple.tuple(12L, 102L, 4, true));
        assertThat(cartItemRepository.findByUserIdOrderByIdAsc(buyer)).extracting(CartItem::getQuantity)
                .containsExactly(3, 4);
        verify(cartRedisRepository).replace(buyer, Map.of(11L, 3, 12L, 4));
    }

    @Test
    @DisplayName("담기 — 없는 옵션 · 구매 불가가 하나라도 있으면 전부 거절, 저장 · Redis 갱신 없음")
    void add_rejectsNotPurchasable() {
        // given
        long buyer = BUYERS.incrementAndGet();
        givenOptions(option(11L, 101L, true), option(13L, 103L, false));   // 12 는 응답에서 빠짐

        // when · then
        assertThatThrownBy(() -> cartService.add(buyer, request(item(11L, 1), item(12L, 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CartErrorCode.CART_PRODUCT_NOT_PURCHASABLE);
        assertThatThrownBy(() -> cartService.add(buyer, request(item(13L, 1))))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CartErrorCode.CART_PRODUCT_NOT_PURCHASABLE);
        assertThat(cartItemRepository.findByUserIdOrderByIdAsc(buyer)).isEmpty();
        verifyNoInteractions(cartRedisRepository);
    }

    @Test
    @DisplayName("담기 — 옵션당 999개를 넘으면 거절하고 같은 요청의 다른 옵션도 롤백")
    void add_rejectsOverMaxAndRollsBack() {
        // given
        long buyer = BUYERS.incrementAndGet();
        givenOptions(option(11L, 101L, true), option(12L, 102L, true));
        cartService.add(buyer, request(item(12L, 990)));

        // when · then
        assertThatThrownBy(() -> cartService.add(buyer, request(item(11L, 1), item(12L, 10))))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CartErrorCode.CART_QUANTITY_EXCEEDED);
        assertThat(cartItemRepository.findByUserIdOrderByIdAsc(buyer))
                .extracting(CartItem::getProductOptionId, CartItem::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(12L, 990));
    }

    @Test
    @DisplayName("삭제 — 단건 · 선택한 옵션만 지우고 없는 옵션은 건너뛰며, 다 지우면 Redis 도 빈 장바구니로")
    void remove_selected() {
        // given
        long buyer = BUYERS.incrementAndGet();
        givenOptions(option(11L, 101L, true), option(12L, 102L, true), option(13L, 103L, true));
        cartService.add(buyer, request(item(11L, 1), item(12L, 2), item(13L, 3)));

        // when
        CartItemsResponse selected = cartService.remove(buyer, List.of(11L, 13L, 99L));
        CartItemsResponse all = cartService.remove(buyer, List.of(12L));

        // then
        assertThat(selected.items()).extracting(CartItemsResponse.Item::productOptionId).containsExactly(12L);
        assertThat(all.cartCount()).isZero();
        assertThat(cartItemRepository.findByUserIdOrderByIdAsc(buyer)).isEmpty();
        verify(cartRedisRepository).replace(buyer, Map.of(12L, 2));
        verify(cartRedisRepository).replace(buyer, Map.of());
    }

    @Test
    @DisplayName("결제 확정 — 주문 PAID 와 같은 트랜잭션에서 주문한 옵션만 장바구니에서 지운다")
    void markPaid_clearsOrderedOptions() {
        // given
        long buyer = BUYERS.incrementAndGet();
        givenOptions(option(11L, 101L, true), option(12L, 102L, true), option(13L, 103L, true));
        cartService.add(buyer, request(item(11L, 1), item(12L, 2), item(13L, 1)));
        Order order = createOrder(buyer, 11L, 12L);

        // when
        orderTx.markPaid(order.getId());

        // then
        assertThat(cartItemRepository.findByUserIdOrderByIdAsc(buyer)).extracting(CartItem::getProductOptionId)
                .containsExactly(13L);
        verify(cartRedisRepository).replace(buyer, Map.of(13L, 1));
    }

    private void givenOptions(ProductOptionResponse... options) {
        given(productClient.getOptions(anyList())).willReturn(Arrays.asList(options));
    }

    private Order createOrder(long buyer, Long... optionIds) {
        List<OrderPricing.Line> lines = Arrays.stream(optionIds)
                .map(id -> new OrderPricing.Line(option(id, id + 90, true), 1)).toList();
        OrderPricing.Result priced = OrderPricing.calculate(lines, storeId -> new ShippingPolicy(3_000L, 50_000L), 0L);
        OrderCreateRequest request = new OrderCreateRequest(
                Arrays.stream(optionIds).map(id -> new OrderCreateRequest.Item(id, 1)).toList(), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, 0L);
        return orderTx.createPending(new UserSummaryResponse(buyer, "buyer@example.com", "홍길동", null), request, priced,
                Map.of(1L, "곳간상회")).order();
    }

    private static ProductOptionResponse option(long optionId, long productId, boolean purchasable) {
        return new ProductOptionResponse(optionId, productId, "상품 " + productId, "옵션 " + optionId, 1L, 301L, 3L,
                10_000L, 0L, 10_000L, 100, purchasable, null);
    }

    private static CartItemAddRequest request(CartItemAddRequest.Item... items) {
        return new CartItemAddRequest(List.of(items));
    }

    private static CartItemAddRequest.Item item(long optionId, int quantity) {
        return new CartItemAddRequest.Item(optionId, quantity);
    }
}
