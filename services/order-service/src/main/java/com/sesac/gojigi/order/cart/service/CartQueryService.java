package com.sesac.gojigi.order.cart.service;

import com.sesac.gojigi.order.cart.dto.response.CartResponse;
import com.sesac.gojigi.order.cart.repository.CartItemRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository.CachedLine;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.ShippingPolicyClient;
import com.sesac.gojigi.order.order.client.StoreClientAdapter;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
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
 * 장바구니에는 "무엇을 몇 개 · 선택했는지"만 있고 가격 · 품절 · 상품명은 조회 때 product 에서 다시, 스토어명은 store 에서 (09장).
 * 원격 호출이 있어 트랜잭션을 걸지 않는다 (MySQL 읽기는 저장소 메서드 하나).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartQueryService {

    private final CartItemRepository cartItemRepository;
    private final CartRedisRepository cartRedisRepository;
    private final ProductClientAdapter productClient;
    private final StoreClientAdapter storeClient;
    private final ShippingPolicyClient shippingPolicy;

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
        ShippingPolicy policy = shippingPolicy.getShippingPolicy(storeId);
        long fee = productAmt == 0 ? 0L : policy.feeFor(productAmt);
        long toFree = fee == 0 ? 0L : Math.max(0L, policy.freeThreshold() - productAmt);
        return new CartResponse.StoreGroup(storeId, storeName(storeId), productAmt, fee, policy.freeThreshold(), toFree,
                items);
    }

    /** 스토어명 — store 장애 · 없는 스토어여도 장바구니는 보여 준다 (이름만 비움) */
    private String storeName(Long storeId) {
        try {
            return storeClient.getStore(storeId).storeName();
        } catch (RuntimeException e) {
            log.warn("장바구니 스토어명 조회 실패 — 이름 없이 응답 - storeId={}, reason={}", storeId, e.toString());
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
