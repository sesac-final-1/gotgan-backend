package com.sesac.gojigi.order.cart.service;

import com.sesac.gojigi.order.cart.dto.request.CartItemAddRequest;
import com.sesac.gojigi.order.cart.dto.request.CartItemUpdateRequest;
import com.sesac.gojigi.order.cart.dto.response.CartItemsResponse;
import com.sesac.gojigi.order.cart.entity.CartItem;
import com.sesac.gojigi.order.cart.exception.CartErrorCode;
import com.sesac.gojigi.order.cart.exception.CartException;
import com.sesac.gojigi.order.cart.repository.CartItemRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository;
import com.sesac.gojigi.order.cart.repository.CartRedisRepository.CachedLine;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.service.OrderTxService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 장바구니 담기 · 수량 · 선택 · 삭제 (ORD-01) — MySQL cart_item 에 먼저 쓰고, 커밋 뒤 Redis cart:{userId} 를 그 회원 장바구니 전체로 갱신 (09장).
 * 결제가 확정되면 주문한 옵션을 장바구니에서 지운다 (주문 PAID 와 같은 트랜잭션).
 */
@Service
public class CartService {

    static final int MAX_QUANTITY = 999;

    private final CartItemRepository cartItemRepository;
    private final CartRedisRepository cartRedisRepository;
    private final ProductClientAdapter productClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate tx;

    /** 커밋 뒤 Redis 에 쓸 그 회원 장바구니 (옵션 ID → 수량 · 선택) */
    record Changed(Long userId, Map<Long, CachedLine> lines) {
    }

    public CartService(CartItemRepository cartItemRepository, CartRedisRepository cartRedisRepository,
            ProductClientAdapter productClient, ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.cartItemRepository = cartItemRepository;
        this.cartRedisRepository = cartRedisRepository;
        this.productClient = productClient;
        this.eventPublisher = eventPublisher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 담기 — product 확인은 트랜잭션 밖에서, 이미 담긴 옵션은 수량을 더한다 */
    public CartItemsResponse add(Long userId, CartItemAddRequest request) {
        Map<Long, Integer> adding = request.items().stream().collect(Collectors.toMap(
                CartItemAddRequest.Item::productOptionId, CartItemAddRequest.Item::quantity, Integer::sum,
                LinkedHashMap::new));
        Map<Long, ProductOptionResponse> options = purchasable(adding.keySet());
        return tx.execute(s -> {
            Map<Long, CartItem> cart = cartItemRepository.findByUserIdOrderByIdAsc(userId).stream()
                    .collect(Collectors.toMap(CartItem::getProductOptionId, Function.identity()));
            adding.forEach((optionId, quantity) -> {
                CartItem item = cart.get(optionId);
                if ((item == null ? 0 : item.getQuantity()) + quantity > MAX_QUANTITY) {
                    throw new CartException(CartErrorCode.CART_QUANTITY_EXCEEDED);
                }
                if (item == null) {
                    cartItemRepository.save(CartItem.builder().userId(userId)
                            .productId(options.get(optionId).productId()).productOptionId(optionId).quantity(quantity)
                            .build());
                } else {
                    item.addQuantity(quantity);
                }
            });
            return changed(userId);
        });
    }

    /** 수량 · 선택 변경 — 보낸 것만. 장바구니에 없는 옵션이면 404 */
    @Transactional
    public CartItemsResponse update(Long userId, Long productOptionId, CartItemUpdateRequest request) {
        CartItem item = cartItemRepository.findByUserIdAndProductOptionId(userId, productOptionId)
                .orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));
        if (request.quantity() != null) {
            item.changeQuantity(request.quantity());
        }
        if (request.checked() != null) {
            item.check(request.checked());
        }
        return changed(userId);
    }

    /** 선택 한꺼번에 — optionIds 가 없으면 전체 (전체 선택 · 해제), 장바구니에 없는 옵션은 건너뛴다 */
    @Transactional
    public CartItemsResponse check(Long userId, List<Long> optionIds, boolean checked) {
        cartItemRepository.findByUserIdOrderByIdAsc(userId).stream()
                .filter(i -> optionIds == null || optionIds.contains(i.getProductOptionId()))
                .forEach(i -> i.check(checked));
        return changed(userId);
    }

    /** 단건 · 선택 삭제 — 장바구니에 없는 옵션은 건너뛴다 */
    @Transactional
    public CartItemsResponse remove(Long userId, List<Long> optionIds) {
        cartItemRepository.deleteOptions(userId, optionIds);
        return changed(userId);
    }

    /** 결제 확정(OrderTxService#markPaid) 트랜잭션 안에서 — 주문이 롤백되면 장바구니도 그대로 */
    @EventListener
    public void onOrderPaid(OrderTxService.Paid paid) {
        cartItemRepository.deleteOptions(paid.buyerId(), paid.productOptionIds());
        changed(paid.buyerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(Changed changed) {
        cartRedisRepository.replace(changed.userId(), changed.lines());
    }

    /** 없는 옵션(응답에서 빠짐) · 구매 불가가 하나라도 있으면 전부 거절 */
    private Map<Long, ProductOptionResponse> purchasable(Collection<Long> optionIds) {
        Map<Long, ProductOptionResponse> options = productClient.getOptions(List.copyOf(optionIds)).stream()
                .filter(ProductOptionResponse::purchasable)
                .collect(Collectors.toMap(ProductOptionResponse::productOptionId, Function.identity()));
        if (!options.keySet().containsAll(optionIds)) {
            throw new CartException(CartErrorCode.CART_PRODUCT_NOT_PURCHASABLE);
        }
        return options;
    }

    /** MySQL 원본 → 캐시 줄 (CartQueryService 의 읽기 미스 채우기도 같은 모양) */
    static Map<Long, CachedLine> cached(List<CartItem> cart) {
        return cart.stream().collect(Collectors.toMap(CartItem::getProductOptionId,
                i -> new CachedLine(i.getQuantity(), i.isChecked()), (a, b) -> a, TreeMap::new));
    }

    private CartItemsResponse changed(Long userId) {
        List<CartItem> cart = cartItemRepository.findByUserIdOrderByIdAsc(userId);
        eventPublisher.publishEvent(new Changed(userId, cached(cart)));
        return CartItemsResponse.of(cart);
    }
}
