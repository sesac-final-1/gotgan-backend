package com.sesac.gojigi.order.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.cart.dto.response.CartResponse;
import com.sesac.gojigi.order.cart.entity.CartItem;
import com.sesac.gojigi.order.cart.repository.CartItemRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository.CachedLine;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.StoreClientAdapter;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.StoreResponse;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartQueryServiceTest {

    @Mock private CartItemRepository cartItemRepository;
    @Mock private CartRedisRepository cartRedisRepository;
    @Mock private ProductClientAdapter productClient;
    @Mock private StoreClientAdapter storeClient;
    @InjectMocks private CartQueryService cartQuery;

    @BeforeEach
    void setUp() {
        given(storeClient.getStore(1L)).willReturn(new StoreResponse(1L, 2L, "곳간상회", "ACTIVE", 3_000L, 50_000L));
        given(storeClient.getStore(2L)).willThrow(new OrderException(OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE));   // 없는 스토어
        given(storeClient.getStore(3L)).willReturn(new StoreResponse(3L, 5L, "무료없음상회", "ACTIVE", 3_500L, null));
        given(storeClient.getStore(4L)).willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "store down"));
    }

    private static ProductOptionResponse option(long optionId, long storeId, long unitPrice, boolean purchasable) {
        return new ProductOptionResponse(optionId, optionId + 100, "상품 " + optionId, "특", storeId, 301L, 3L, unitPrice,
                0L, unitPrice, 7, purchasable, "https://img/" + optionId);
    }

    @Test
    @DisplayName("캐시 적중 — 스토어별 묶음 · 선택된 구매 가능 줄만 금액 · 배송비 · 무료배송까지 남은 금액, 없는 옵션은 unavailable, 없는 스토어는 이름 · 배송비 없이")
    void groupsAndTotals() {
        // given — 스토어1: 선택 20,000 + 선택 해제 5,000 + 구매 불가 9,000 / 스토어2: 선택 60,000(무료) / 99: product 에 없음
        Map<Long, CachedLine> lines = new TreeMap<>(Map.of(11L, new CachedLine(2, true), 12L, new CachedLine(1, false),
                13L, new CachedLine(1, true), 21L, new CachedLine(3, true), 99L, new CachedLine(1, true)));
        given(cartRedisRepository.find(4L)).willReturn(Optional.of(lines));
        given(productClient.getOptions(List.of(11L, 12L, 13L, 21L, 99L))).willReturn(List.of(option(11L, 1L, 10_000L, true),
                option(12L, 1L, 5_000L, true), option(13L, 1L, 9_000L, false), option(21L, 2L, 20_000L, true)));

        // when
        CartResponse cart = cartQuery.get(4L);

        // then
        assertThat(cart.stores()).extracting(CartResponse.StoreGroup::storeId, CartResponse.StoreGroup::storeName,
                        CartResponse.StoreGroup::productAmt, CartResponse.StoreGroup::shippingFee, CartResponse.StoreGroup::amountToFree)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, "곳간상회", 20_000L, 3_000L, 30_000L),
                        org.assertj.core.groups.Tuple.tuple(2L, null, 60_000L, 0L, 0L));
        assertThat(cart.stores()).extracting(CartResponse.StoreGroup::freeThreshold).containsExactly(50_000L, null);
        CartResponse.Line first = cart.stores().get(0).items().get(0);
        assertThat(first.productName()).isEqualTo("상품 11");
        assertThat(first.lineAmt()).isEqualTo(20_000L);
        assertThat(cart.stores().get(0).items()).extracting(CartResponse.Line::purchasable).containsExactly(true, true, false);
        assertThat(cart.unavailable()).extracting(CartResponse.UnavailableItem::productOptionId).containsExactly(99L);
        assertThat(cart.cartCount()).isEqualTo(5);
        assertThat(cart.checkedCount()).isEqualTo(4);
        assertThat(cart.totalProductAmt()).isEqualTo(80_000L);
        assertThat(cart.totalShippingFee()).isEqualTo(3_000L);
        assertThat(cart.totalAmt()).isEqualTo(83_000L);
        verifyNoInteractions(cartItemRepository);
    }

    @Test
    @DisplayName("스토어에서 아무것도 선택하지 않으면 배송비 0 · 남은 금액 0")
    void nothingCheckedInStore() {
        given(cartRedisRepository.find(4L)).willReturn(Optional.of(new TreeMap<>(Map.of(11L, new CachedLine(1, false)))));
        given(productClient.getOptions(List.of(11L))).willReturn(List.of(option(11L, 1L, 10_000L, true)));

        CartResponse.StoreGroup group = cartQuery.get(4L).stores().get(0);

        assertThat(group.shippingFee()).isZero();
        assertThat(group.amountToFree()).isZero();
    }

    @Test
    @DisplayName("무료배송 기준이 없는 스토어 — 금액과 상관없이 기본 배송비, 무료까지 남은 금액 0")
    void noFreeShipping() {
        given(cartRedisRepository.find(4L)).willReturn(Optional.of(new TreeMap<>(Map.of(31L, new CachedLine(1, true)))));
        given(productClient.getOptions(List.of(31L))).willReturn(List.of(option(31L, 3L, 90_000L, true)));

        CartResponse.StoreGroup group = cartQuery.get(4L).stores().get(0);

        assertThat(group.storeName()).isEqualTo("무료없음상회");
        assertThat(group.shippingFee()).isEqualTo(3_500L);
        assertThat(group.freeThreshold()).isNull();
        assertThat(group.amountToFree()).isZero();
    }

    @Test
    @DisplayName("store 장애 — 배송비를 알 수 없어 502 를 그대로 (틀린 배송비를 보여 주지 않음)")
    void storeDown_propagates() {
        given(cartRedisRepository.find(4L)).willReturn(Optional.of(new TreeMap<>(Map.of(41L, new CachedLine(1, true)))));
        given(productClient.getOptions(List.of(41L))).willReturn(List.of(option(41L, 4L, 10_000L, true)));

        assertThatThrownBy(() -> cartQuery.get(4L)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("캐시 미스 · 장애 — MySQL 원본을 읽고 캐시를 다시 채운다, 빈 장바구니면 product 를 부르지 않는다")
    void missReadsMysql() {
        // given
        given(cartRedisRepository.find(4L)).willReturn(Optional.empty());
        given(cartRedisRepository.find(5L)).willReturn(Optional.empty());
        given(cartItemRepository.findByUserIdOrderByIdAsc(4L)).willReturn(List.of(
                CartItem.builder().userId(4L).productId(111L).productOptionId(11L).quantity(2).build()));
        given(cartItemRepository.findByUserIdOrderByIdAsc(5L)).willReturn(List.of());
        given(productClient.getOptions(List.of(11L))).willReturn(List.of(option(11L, 1L, 10_000L, true)));

        // when
        CartResponse cart = cartQuery.get(4L);
        CartResponse empty = cartQuery.get(5L);

        // then
        assertThat(cart.totalProductAmt()).isEqualTo(20_000L);
        verify(cartRedisRepository).replace(4L, Map.of(11L, new CachedLine(2, true)));
        assertThat(empty.cartCount()).isZero();
        assertThat(empty.stores()).isEmpty();
        verify(cartRedisRepository, never()).replace(org.mockito.ArgumentMatchers.eq(5L), any());
        verify(productClient, never()).getOptions(List.of());
    }
}
