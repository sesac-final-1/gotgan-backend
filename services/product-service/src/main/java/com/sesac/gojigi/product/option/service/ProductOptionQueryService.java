package com.sesac.gojigi.product.option.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.option.dto.response.ProductOptionResponse;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.option.repository.ProductOptionRepository;
import com.sesac.gojigi.product.product.entity.HiddenReason;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가격 · 구매 가능 조회 (PRD-10, product.md 4절) — order 가 장바구니 · 주문서 · 주문 Saga 1단계(상품주문 스냅샷)에서 부른다.
 * DB 조회만 하므로 order 가 재시도해도 안전하다. 없는 ID 는 응답에서 빠지고 order 가 구매 불가로 처리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductOptionQueryService {

    static final int MAX_IDS = 100;

    private final ProductOptionRepository productOptionRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ImageUrls imageUrls;

    /** 요청 순서대로(중복 ID 는 한 번), 없는 ID 는 뺀다. 쿼리는 ID 개수와 상관없이 3번 (옵션+상품 · 대표 이미지 · 카테고리) */
    public List<ProductOptionResponse> findOptions(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "옵션 ID 는 1~100개를 보내 주세요.");
        }
        Map<Long, ProductOption> options = productOptionRepository.findWithProductByIdIn(ids).stream()
                .collect(Collectors.toMap(ProductOption::getId, Function.identity()));
        if (options.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = options.values().stream().map(o -> o.getProduct().getId()).distinct().toList();
        Map<Long, String> imageKeys = productRepository.findMainImageKeys(productIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (String) row[1]));
        Map<Long, Category> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return new LinkedHashSet<>(ids).stream()
                .map(options::get)
                .filter(Objects::nonNull)
                .map(o -> toResponse(o, imageKeys.get(o.getProduct().getId()), categories))
                .toList();
    }

    private ProductOptionResponse toResponse(ProductOption o, String imageKey, Map<Long, Category> categories) {
        Product p = o.getProduct();
        Category category = categories.get(p.getCategoryId());
        Category top = category == null ? null : categories.get(category.getParentId());
        boolean categoryActive = category != null && category.isActive() && top != null && top.isActive();
        boolean purchasable = HiddenReason.of(p, categoryActive) == null && p.getDeletedAt() == null && o.getDeletedAt() == null;
        return new ProductOptionResponse(o.getId(), p.getId(), p.getName(), optionName(o), p.getStoreId(),
                p.getCategoryId(), top == null ? null : top.getId(),
                p.getSalePrice(), o.getExtraPrice(), p.getSalePrice() + o.getExtraPrice(), o.getStock(),
                purchasable, imageUrls.of(imageKey));
    }

    /** "오트밀 / M" · "오트밀" — 옵션 없는 상품(값 둘 다 null)은 null */
    private static String optionName(ProductOption o) {
        String name = Stream.of(o.getOption1Value(), o.getOption2Value()).filter(Objects::nonNull).collect(Collectors.joining(" / "));
        return name.isEmpty() ? null : name;
    }
}
