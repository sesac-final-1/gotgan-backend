package com.sesac.gojigi.product.stock.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.option.repository.ProductOptionRepository;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import com.sesac.gojigi.product.stock.dto.request.StockItem;
import com.sesac.gojigi.product.stock.dto.request.StockReleaseRequest;
import com.sesac.gojigi.product.stock.dto.request.StockReserveRequest;
import com.sesac.gojigi.product.stock.dto.response.StockReservationResponse;
import com.sesac.gojigi.product.stock.entity.StockHistory;
import com.sesac.gojigi.product.stock.entity.StockType;
import com.sesac.gojigi.product.stock.exception.StockErrorCode;
import com.sesac.gojigi.product.stock.exception.StockException;
import com.sesac.gojigi.product.stock.repository.StockHistoryRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 예약 · 해제 (PRD-08, product.md 1 · 2절) — 주문 Saga 3단계와 그 보상. sagaId 멱등.
 * 예약: ① 옵션 행 잠금(ID 오름차순) ② 같은 sagaId 예약 기록 → 처음 응답 ③ 해제 표식 → 409 ④ 없는 옵션 404 · 구매 불가 409
 * ⑤ 조건부 차감(모자라면 409) ⑥ RESERVE 기록. 예외는 트랜잭션 전체를 롤백한다.
 * 해제: ① 같은 옵션 행 잠금 ② 이미 RELEASE → 같은 응답 ③ RESERVE 있으면 그 수량만큼 되돌림 + RELEASE ④ 없으면 수량 0 표식.
 * 예약 · 해제 모두 같은 옵션 행을 먼저 잠그므로, order 가 타임아웃으로 해제를 먼저 보내도 둘 중 하나만 반영된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationService {

    static final int MAX_SAGA_ID = 64;   // stock_history.ref_key VARCHAR(64)

    private final ProductOptionRepository productOptionRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public StockReservationResponse reserve(StockReserveRequest request) {
        String sagaId = request.sagaId();
        List<StockItem> items = sortedWithoutDuplicates(request.items());
        List<Long> ids = items.stream().map(StockItem::productOptionId).toList();

        // ① 반드시 트랜잭션의 첫 쿼리 — 이후 일반 조회의 스냅샷이 잠금을 얻은 뒤로 잡힌다
        Map<Long, ProductOption> options = productOptionRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(ProductOption::getId, Function.identity()));

        List<StockHistory> reserved = stockHistoryRepository.findByTypeAndRefKeyOrderByProductOptionId(StockType.RESERVE, sagaId);
        if (!reserved.isEmpty()) {
            log.info("재고 예약 재요청 - 처음 응답 반환, sagaId={}", sagaId);
            return response(sagaId, reserved);
        }
        if (stockHistoryRepository.existsByTypeAndRefKey(StockType.RELEASE, sagaId)) {
            log.warn("해제된 주문의 늦은 재고 예약 거절 - sagaId={}", sagaId);
            throw new StockException(StockErrorCode.STOCK_RESERVATION_RELEASED);
        }
        checkPurchasable(ids, options);

        for (StockItem item : items) {
            if (productOptionRepository.decreaseStock(item.productOptionId(), item.quantity()) == 0) {
                throw new StockException(StockErrorCode.STOCK_INSUFFICIENT,
                        "재고가 부족한 상품이 있습니다. (옵션 " + item.productOptionId() + ")");
            }
        }
        List<StockHistory> saved = stockHistoryRepository.saveAll(items.stream()
                .map(item -> StockHistory.of(item.productOptionId(), StockType.RESERVE, sagaId, item.quantity()))
                .toList());
        log.info("재고 예약 - sagaId={}, options={}", sagaId, ids);
        return response(sagaId, saved);
    }

    /**
     * 해제 — 되돌릴 게 없으면 표식만 남기고, 옵션 · 상품이 그 사이 삭제 · 판매중지돼도 재고 수는 돌려놓는다.
     * 응답 items 는 실제로 되돌린 수량(옵션 ID 오름차순), 예약이 없었으면 빈 목록.
     */
    @Transactional
    public StockReservationResponse release(String sagaId, StockReleaseRequest request) {
        if (sagaId.length() > MAX_SAGA_ID) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "sagaId 는 64자 이하여야 합니다.");
        }
        List<StockItem> items = sortedWithoutDuplicates(request.items());
        List<Long> ids = items.stream().map(StockItem::productOptionId).toList();

        // ① 예약과 같은 옵션 행을 같은 순서로 — 반드시 트랜잭션의 첫 쿼리
        Map<Long, ProductOption> options = productOptionRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(ProductOption::getId, Function.identity()));

        List<StockHistory> released = stockHistoryRepository.findByTypeAndRefKeyOrderByProductOptionId(StockType.RELEASE, sagaId);
        if (!released.isEmpty()) {
            log.info("재고 해제 재요청 - 처음 응답 반환, sagaId={}", sagaId);
            return response(sagaId, released.stream().filter(h -> h.getQuantity() > 0).toList());
        }
        List<StockHistory> reserved = stockHistoryRepository.findByTypeAndRefKeyOrderByProductOptionId(StockType.RESERVE, sagaId);
        if (reserved.isEmpty()) {
            // 예약 전에 실패했거나 예약이 아직 처리 중 — 늦게 도착한 예약이 보고 롤백하도록 표식 (없는 옵션은 FK 때문에 건너뜀)
            stockHistoryRepository.saveAll(ids.stream().filter(options::containsKey)
                    .map(id -> StockHistory.of(id, StockType.RELEASE, sagaId, 0))
                    .toList());
            log.info("재고 해제 표식 - 예약 없음, sagaId={}, options={}", sagaId, ids);
            return new StockReservationResponse(sagaId, List.of());
        }
        for (StockHistory r : reserved) {
            productOptionRepository.increaseStock(r.getProductOptionId(), r.getQuantity());
        }
        List<StockHistory> saved = stockHistoryRepository.saveAll(reserved.stream()
                .map(r -> StockHistory.of(r.getProductOptionId(), StockType.RELEASE, sagaId, r.getQuantity()))
                .toList());
        log.info("재고 해제 - sagaId={}, options={}", sagaId, saved.stream().map(StockHistory::getProductOptionId).toList());
        return response(sagaId, saved);
    }

    /** 옵션 ID 오름차순(데드락 방지 · 응답 순서). 같은 옵션이 두 번이면 order 버그 → 400 */
    private static List<StockItem> sortedWithoutDuplicates(List<StockItem> items) {
        List<StockItem> sorted = items.stream().sorted(Comparator.comparing(StockItem::productOptionId)).toList();
        if (sorted.stream().map(StockItem::productOptionId).distinct().count() != sorted.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "같은 옵션이 두 번 들어 있습니다.");
        }
        return sorted;
    }

    /** 없는 옵션 404, 하나라도 구매 불가면 409 — 4절 조회와 같은 기준(ProductOption.isPurchasable) */
    private void checkPurchasable(List<Long> ids, Map<Long, ProductOption> options) {
        if (options.size() != ids.size()) {
            List<Long> missing = ids.stream().filter(id -> !options.containsKey(id)).toList();
            throw new ProductException(ProductErrorCode.PRODUCT_OPTION_NOT_FOUND, "상품 옵션을 찾을 수 없습니다. (옵션 " + missing + ")");
        }
        Map<Long, Category> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        for (ProductOption option : options.values()) {
            if (!option.isPurchasable(categoryActive(option.getProduct(), categories))) {
                throw new ProductException(ProductErrorCode.PRODUCT_NOT_PURCHASABLE,
                        "현재 구매할 수 없는 상품이 있습니다. (옵션 " + option.getId() + ")");
            }
        }
    }

    private static boolean categoryActive(Product product, Map<Long, Category> categories) {
        Category category = categories.get(product.getCategoryId());
        Category top = category == null ? null : categories.get(category.getParentId());
        return category != null && category.isActive() && top != null && top.isActive();
    }

    private static StockReservationResponse response(String sagaId, List<StockHistory> histories) {
        return new StockReservationResponse(sagaId, histories.stream()
                .map(h -> new StockItem(h.getProductOptionId(), h.getQuantity()))
                .toList());
    }
}
