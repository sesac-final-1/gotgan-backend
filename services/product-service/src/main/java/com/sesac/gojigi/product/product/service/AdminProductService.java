package com.sesac.gojigi.product.product.service;

import com.sesac.gojigi.product.product.dto.request.ProductBlockRequest;
import com.sesac.gojigi.product.product.dto.response.ProductBlockResponse;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 강제 판매중지 (PRD-13 · product.md 6-2절) — 스토어 상품 목록(6-1절)은 판매자 목록과 같아 SellerProductService.list 를 쓴다 */
@Service
@RequiredArgsConstructor
public class AdminProductService {

    private final ProductRepository productRepository;

    /** 강제 판매중지 · 해제 (6-2절) — 해제하면 STOPPED(판매자가 점검 후 재개), BLOCKED 가 아니면 그대로 200 */
    @Transactional
    public ProductBlockResponse block(Long productId, ProductBlockRequest request) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
        if (request.blocked()) {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new ProductException(ProductErrorCode.PRODUCT_BLOCK_REASON_REQUIRED);
            }
            product.block(request.reason().strip());
        } else {
            product.unblock();
        }
        return ProductBlockResponse.from(product);
    }
}
