package com.sesac.gojigi.product.stock.service;

import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.option.repository.ProductOptionRepository;
import com.sesac.gojigi.product.stock.dto.request.StockItem;
import com.sesac.gojigi.product.stock.dto.request.StockReserveRequest;
import com.sesac.gojigi.product.stock.entity.StockType;
import com.sesac.gojigi.product.stock.exception.StockErrorCode;
import com.sesac.gojigi.product.stock.exception.StockException;
import com.sesac.gojigi.product.stock.repository.StockHistoryRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 예약 전 빠른 거절 (product.md 1절, k6 지-4) — 품절이면 옵션 행 잠금을 기다리지 않고 바로 409.
 * 재고가 있거나 부족 말고 다른 응답이 나올 수 있으면 아무것도 하지 않고 예약(잠금)에 맡긴다.
 * 차감은 하지 않으므로 초과 판매와 무관하다. 예약 트랜잭션의 스냅샷에 영향을 주지 않도록 별도 트랜잭션으로 둔다.
 */
@Service
@RequiredArgsConstructor
public class StockFastRejectService {

    private final ProductOptionRepository productOptionRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public void rejectIfOutOfStock(StockReserveRequest request) {
        List<StockItem> items = request.items();
        List<Long> ids = items.stream().map(StockItem::productOptionId).distinct().toList();
        Map<Long, ProductOption> options = productOptionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ProductOption::getId, Function.identity()));
        if (ids.size() != items.size() || options.size() != ids.size()) {
            return;   // 같은 옵션 중복 400 · 없는 옵션 404 는 예약이 판단
        }
        StockItem shortage = items.stream()
                .filter(item -> options.get(item.productOptionId()).getStock() < item.quantity())
                .findFirst().orElse(null);
        if (shortage == null) {
            return;
        }
        String sagaId = request.sagaId();
        if (stockHistoryRepository.existsByTypeAndRefKey(StockType.RESERVE, sagaId)
                || stockHistoryRepository.existsByTypeAndRefKey(StockType.RELEASE, sagaId)) {
            return;   // 같은 sagaId 재요청은 처음 응답 · 해제 표식은 409 RELEASED — 재고가 0 이어도 예약이 판단
        }
        Map<Long, Category> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        boolean allPurchasable = options.values().stream().allMatch(option ->
                option.isPurchasable(StockReservationService.categoryActive(option.getProduct(), categories)));
        if (!allPurchasable) {
            return;   // 구매 불가 409 가 재고 부족보다 먼저 — 예약과 같은 순서
        }
        throw new StockException(StockErrorCode.STOCK_INSUFFICIENT,
                "재고가 부족한 상품이 있습니다. (옵션 " + shortage.productOptionId() + ")");
    }
}
