package com.sesac.gojigi.product.stock.service;

import com.sesac.gojigi.product.option.repository.ProductOptionRepository;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import com.sesac.gojigi.product.stock.dto.request.StockRestoreRequest;
import com.sesac.gojigi.product.stock.entity.StockHistory;
import com.sesac.gojigi.product.stock.entity.StockType;
import com.sesac.gojigi.product.stock.repository.StockHistoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 복원 (PRD-09, product.md 3절) — 취소 · 반품 Saga. claimId 멱등(ref_key = claim-{claimId}).
 * 순서: ① 옵션 행 잠금(없으면 404) ② 같은 claimId RESTORE 기록이 있으면 아무것도 안 함 ③ stock + qty + RESTORE 기록.
 * 옵션 · 상품이 판매중지 · 삭제돼도 복원한다 — 결제완료 즉시 취소 Saga 가 이미 부르고, 실패하면 같은 claimId 로 다시 부른다.
 * 복원 수량이 실제 판매 수량을 넘는지는 product 가 알 수 없다(order 가 클레임 수량 그대로 보냄).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRestorationService {

    private final ProductOptionRepository productOptionRepository;
    private final StockHistoryRepository stockHistoryRepository;

    @Transactional
    public void restore(StockRestoreRequest request) {
        Long optionId = request.productOptionId();
        String refKey = "claim-" + request.claimId();

        // ① 반드시 트랜잭션의 첫 쿼리 — 같은 claimId 동시 재요청이 줄을 서고, 이후 조회가 앞 요청의 커밋을 본다
        if (productOptionRepository.findAllByIdForUpdate(List.of(optionId)).isEmpty()) {
            throw new ProductException(ProductErrorCode.PRODUCT_OPTION_NOT_FOUND, "상품 옵션을 찾을 수 없습니다. (옵션 " + optionId + ")");
        }
        if (stockHistoryRepository.existsByTypeAndRefKey(StockType.RESTORE, refKey)) {
            log.info("재고 복원 재요청 - 이미 처리됨, claimId={}", request.claimId());
            return;
        }
        productOptionRepository.increaseStock(optionId, request.quantity());
        stockHistoryRepository.save(StockHistory.of(optionId, StockType.RESTORE, refKey, request.quantity()));
        log.info("재고 복원 - claimId={}, optionId={}, quantity={}", request.claimId(), optionId, request.quantity());
    }
}
