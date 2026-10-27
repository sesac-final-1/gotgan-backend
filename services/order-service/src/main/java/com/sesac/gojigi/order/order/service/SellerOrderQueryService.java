package com.sesac.gojigi.order.order.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.order.dto.response.SellerOrderItemListResponse;
import com.sesac.gojigi.order.order.dto.response.SellerOrderItemResponse;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 주문 조회 (목록 · 상세) — 내 스토어 상품주문만, 결제 전(ORDERED)은 판매자에게 보이지 않는다 */
@Service
@RequiredArgsConstructor
public class SellerOrderQueryService {

    /** 판매자에게 보이는 상태 — 결제 대기 · 실패 주문의 상품주문(ORDERED)만 뺀다 */
    static final Set<OrderItemStatus> VISIBLE = EnumSet.complementOf(EnumSet.of(OrderItemStatus.ORDERED));

    private final OrderItemRepository orderItemRepository;

    /** status 가 없으면 전체 탭. ORDERED 를 고르면 빈 목록 */
    @Transactional(readOnly = true)
    public SellerOrderItemListResponse list(Long storeId, OrderItemStatus status, String q, int page, int size) {
        requireStore(storeId);
        Set<OrderItemStatus> statuses = status == null ? VISIBLE : EnumSet.of(status);
        PageRequest pageable = PageRequest.of(page, size);
        Page<SellerOrderItemResponse> items = !VISIBLE.containsAll(statuses) ? Page.empty(pageable)
                : orderItemRepository.searchSeller(storeId, statuses, q == null || q.isBlank() ? null : q.trim(), pageable)
                        .map(SellerOrderItemResponse::from);
        return new SellerOrderItemListResponse(statusCounts(storeId), PageResponse.from(items));
    }

    /** 상세 — 없거나 남의 스토어 · 결제 전 상품주문은 같은 404 */
    @Transactional(readOnly = true)
    public SellerOrderItemResponse get(Long storeId, Long orderItemId) {
        requireStore(storeId);
        return orderItemRepository.findById(orderItemId)
                .filter(i -> i.getStoreId().equals(storeId) && VISIBLE.contains(i.getStatus()))
                .map(SellerOrderItemResponse::from)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    /** 보이는 상태 전부를 키로 (없으면 0) */
    private Map<OrderItemStatus, Long> statusCounts(Long storeId) {
        Map<OrderItemStatus, Long> counts = new EnumMap<>(OrderItemStatus.class);
        VISIBLE.forEach(s -> counts.put(s, 0L));
        orderItemRepository.countByStatus(storeId).forEach(r -> {
            OrderItemStatus s = (OrderItemStatus) r[0];
            if (VISIBLE.contains(s)) {
                counts.put(s, ((Number) r[1]).longValue());
            }
        });
        return counts;
    }

    private static void requireStore(Long storeId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다.");
        }
    }
}
