package com.sesac.gojigi.order.cart.service;

import com.sesac.gojigi.order.cart.dto.response.CartResponse;
import com.sesac.gojigi.order.cart.repository.CartItemRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository.CachedLine;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.StoreClientAdapter;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.StoreResponse;
import com.sesac.gojigi.order.order.exception.OrderException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 장바구니 조회 (ORD-02) — 읽기는 Redis cart:{userId} → 미스 · 장애면 MySQL 원본(읽은 뒤 캐시를 다시 채움, read-through).
 * 장바구니에는 "무엇을 몇 개 · 선택했는지"만 있고 가격 · 품절 · 상품명은 조회 때 product 에서 다시, 스토어명 · 배송비 정책은 store 에서 (09장).
 * 원격 호출이 있어 트랜잭션을 걸지 않는다 (MySQL 읽기는 저장소 메서드 하나).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartQueryService {

    /** 없는 스토어 — 배송비 · 무료배송 기준 없이 보여 준다 */
    private static final ShippingPolicy NO_STORE = new ShippingPolicy(0L, null);

    private final CartItemRepository cartItemRepository;
    private final CartRedisRepository cartRedisRepository;
    private final ProductClientAdapter productClient;
    private final StoreClientAdapter storeClient;

    public CartResponse get(Long userId) {
        Map<Long, CachedLine> lines = cartRedisRepository.find(userId).orElseGet(() -> load(userId));
        if (lines.isEmpty()) {
            return new CartResponse(List.of(), List.of(), 0, 0, 0L, 0L, 0L);
        }
        Map<Long, ProductOptionResponse> options = productClient.getOptions(List.copyOf(lines.keySet())).stream()
                .collect(Collectors.toMap(ProductOptionResponse::productOptionId, Function.identity()));

        Map<Long, List<CartResponse.Line>> byStore = new LinkedHashMap<>();
        List<CartResponse.UnavailableItem> unavailable = new ArrayList<>();
        lines.forEach((optionId, line) -> {
            ProductOptionResponse o = options.get(optionId);
            if (o == null) {
                unavailable.add(new CartResponse.UnavailableItem(optionId, null, line.quantity()));
                return;
            }
            byStore.computeIfAbsent(o.storeId(), id -> new ArrayList<>()).add(new CartResponse.Line(optionId,
                    o.productId(), o.productName(), o.optionName(), o.imageUrl(), o.unitPrice(), line.quantity(),
                    line.checked(), o.purchasable(), o.stock(), o.unitPrice() * line.quantity()));
        });
        List<CartResponse.StoreGroup> stores = new ArrayList<>();
        byStore.forEach((storeId, items) -> stores.add(group(storeId, items)));

        long productAmt = stores.stream().mapToLong(CartResponse.StoreGroup::productAmt).sum();
        long shippingFee = stores.stream().mapToLong(CartResponse.StoreGroup::shippingFee).sum();
        int checkedCount = (int) lines.values().stream().filter(CachedLine::checked).count();
        return new CartResponse(stores, unavailable, lines.size(), checkedCount, productAmt, shippingFee,
                productAmt + shippingFee);
    }

    /** 스토어 묶음 — 선택된 구매 가능 줄만 금액 · 배송비에 넣는다 */
    private CartResponse.StoreGroup group(Long storeId, List<CartResponse.Line> items) {
        long productAmt = items.stream().filter(l -> l.checked() && l.purchasable())
                .mapToLong(CartResponse.Line::lineAmt).sum();
        StoreResponse store = findStore(storeId);
        ShippingPolicy policy = store == null ? NO_STORE : store.shippingPolicy();
        long fee = productAmt == 0 ? 0L : policy.feeFor(productAmt);
        long toFree = fee == 0 ? 0L : policy.amountToFree(productAmt);
        return new CartResponse.StoreGroup(storeId, store == null ? null : store.storeName(), productAmt, fee,
                policy.freeThreshold(), toFree, items);
    }

    /**
     * 스토어 — 없는(삭제된) 스토어면 null: 그 상품은 product 가 구매 불가로 주므로 이름 · 배송비 없이 보여 준다.
     * store 장애는 배송비를 알 수 없어 그대로 502 (틀린 배송비를 보여 주지 않는다)
     */
    private StoreResponse findStore(Long storeId) {
        try {
            return storeClient.getStore(storeId);
        } catch (OrderException e) {
            log.warn("장바구니 스토어 없음 — 이름 · 배송비 없이 응답 - storeId={}", storeId);
            return null;
        }
    }

    /** 캐시 미스 — MySQL 원본을 읽고 캐시를 다시 채운다 (채우기 실패는 WARN 만) */
    private Map<Long, CachedLine> load(Long userId) {
        Map<Long, CachedLine> lines = CartService.cached(cartItemRepository.findByUserIdOrderByIdAsc(userId));
        if (!lines.isEmpty()) {
            cartRedisRepository.replace(userId, lines);
        }
        return lines;
    }
}
