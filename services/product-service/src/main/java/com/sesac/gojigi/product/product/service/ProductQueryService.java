package com.sesac.gojigi.product.product.service;

import com.querydsl.core.Tuple;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.product.dto.request.ProductSearchRequest;
import com.sesac.gojigi.product.product.dto.response.ProductDetailResponse;
import com.sesac.gojigi.product.product.dto.response.ProductSummaryResponse;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductSort;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import com.sesac.gojigi.product.product.repository.ProductSearchRepository;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

    static final int DEFAULT_SIZE = 20;
    static final int MAX_KEYWORD = 50;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ProductRepository productRepository;
    private final ProductSearchRepository productSearchRepository;
    private final CategoryRepository categoryRepository;
    private final ImageUrls imageUrls;

    /**
     * 상품 목록 · 검색 (9절) — 목록 · 검색 · 메인 화면 · 스토어 홈 공용, 쇼핑몰에 보이는 상품만.
     * 조건에 맞는 상품이 없거나 없는 · 꺼진 카테고리 · 운영 안 하는 스토어면 빈 페이지. 카드의 대표 이미지 · 품절은 묶음 쿼리로 붙인다.
     */
    public PageResponse<ProductSummaryResponse> search(ProductSearchRequest request) {
        ProductSearchRepository.Condition condition = toCondition(request);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_SIZE : request.size();

        List<Tuple> rows = productSearchRepository.search(condition, (long) page * size, size);
        long total = rows.isEmpty() && page == 0 ? 0 : productSearchRepository.count(condition);
        List<Long> ids = rows.stream().map(row -> row.get(0, Product.class).getId()).toList();
        Map<Long, Long> stocks = ids.isEmpty() ? Map.of() : toMap(productRepository.sumStockByProductIds(ids));
        Map<Long, String> imageKeys = ids.isEmpty() ? Map.of() : productRepository.findMainImageKeys(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (String) row[1]));
        List<ProductSummaryResponse> content = rows.stream()
                .map(row -> toSummary(row.get(0, Product.class), row.get(1, Long.class), stocks, imageKeys))
                .toList();
        return PageResponse.from(new PageImpl<>(content, PageRequest.of(page, size), total));
    }

    /** 형식 검사(@Valid) 뒤 남은 규칙 — q 는 앞뒤 공백 제거 후 비면 조건 없음 · 50자 초과 400, min ≤ max */
    private static ProductSearchRepository.Condition toCondition(ProductSearchRequest r) {
        String keyword = r.q() == null || r.q().isBlank() ? null : r.q().strip();
        if (keyword != null && keyword.length() > MAX_KEYWORD) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "검색어는 50자 이하로 입력해 주세요.");
        }
        if (r.minPrice() != null && r.maxPrice() != null && r.minPrice() > r.maxPrice()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "최소 가격이 최대 가격보다 클 수 없습니다.");
        }
        return new ProductSearchRepository.Condition(r.categoryId(), r.storeId(), keyword, r.minPrice(), r.maxPrice(),
                Boolean.TRUE.equals(r.excludeSoldOut()), r.sort() == null ? ProductSort.NEW : r.sort());
    }

    private ProductSummaryResponse toSummary(Product p, Long topCategoryId, Map<Long, Long> stocks, Map<Long, String> imageKeys) {
        return new ProductSummaryResponse(p.getId(), p.getName(), p.getStoreId(), p.getCategoryId(), topCategoryId,
                p.getPrice(), p.getDiscountRate(), p.getSalePrice(),
                imageUrls.of(imageKeys.get(p.getId())), stocks.getOrDefault(p.getId(), 0L) == 0,
                p.getCreatedAt().atZone(KST).toOffsetDateTime());
    }

    /** [productId, 재고 합계(삭제 안 된 옵션)] — 판매자 목록과 같은 묶음 쿼리 */
    private static Map<Long, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).longValue()));
    }

    /** 상품 상세 (10절) — 옵션 · 이미지는 같은 트랜잭션에서 지연 로딩해 DTO 로 바꾼다(open-in-view 꺼짐) */
    public ProductDetailResponse get(Long productId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
        Category category = categoryRepository.findById(product.getCategoryId()).orElseThrow(); // 카테고리는 삭제하지 않는다
        Category top = categoryRepository.findById(category.getParentId()).orElseThrow();
        return ProductDetailResponse.of(product, category, top, imageUrls);
    }
}
