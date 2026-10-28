package com.sesac.gojigi.product.product.service;

import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.product.dto.response.ProductDetailResponse;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 상품 조회 (구매자 화면, product.md 9 · 10절) — 로그인 없음.
 * 상세는 판매중지 등 구매할 수 없는 상품도 보여 주고(purchasable=false), 없거나 삭제된 상품만 404.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ImageUrls imageUrls;

    /** 상품 상세 (10절) — 옵션 · 이미지는 같은 트랜잭션에서 지연 로딩해 DTO 로 바꾼다(open-in-view 꺼짐) */
    public ProductDetailResponse get(Long productId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
        Category category = categoryRepository.findById(product.getCategoryId()).orElseThrow(); // 카테고리는 삭제하지 않는다
        Category top = categoryRepository.findById(category.getParentId()).orElseThrow();
        return ProductDetailResponse.of(product, category, top, imageUrls);
    }
}
