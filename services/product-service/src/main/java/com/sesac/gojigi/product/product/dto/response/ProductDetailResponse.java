package com.sesac.gojigi.product.product.dto.response;

import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.product.entity.HiddenReason;
import com.sesac.gojigi.product.product.entity.Product;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 공개 상품 상세 (product.md 10절) — 구매자 화면. 판매자 상세(12절)에서 판매자만 보는 값(status · 사유 · 미노출 이유 · 재고 수 · 이미지 key)을 뺀 모양.
 * 재고 수는 공개하지 않고 soldOut 만 준다. 옵션 없는 상품도 값이 null 인 옵션 1개 — 장바구니는 언제나 productOptionId 로 담는다.
 */
public record ProductDetailResponse(
        Long productId, Long storeId,
        Long categoryId, String categoryName, Long topCategoryId, String topCategoryName,
        String name, long price, BigDecimal discountRate, long salePrice,
        String description, String material, String origin,
        List<String> images,
        String option1Name, String option2Name,
        List<OptionItem> options,
        boolean purchasable, boolean soldOut,
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record OptionItem(Long productOptionId, String option1Value, String option2Value, long extraPrice, boolean soldOut) {
    }

    /** category = 하위, top = 그 상위. 판매중지 · 강제 판매중지 · 카테고리 꺼짐 · 스토어 비활성이면 purchasable=false (보여 주긴 함) */
    public static ProductDetailResponse of(Product p, Category category, Category top, ImageUrls imageUrls) {
        List<ProductOption> liveOptions = p.getOptions().stream().filter(o -> o.getDeletedAt() == null).toList();
        boolean purchasable = HiddenReason.of(p, category.isActive() && top.isActive()) == null;
        return new ProductDetailResponse(
                p.getId(), p.getStoreId(),
                category.getId(), category.getName(), top.getId(), top.getName(),
                p.getName(), p.getPrice(), p.getDiscountRate(), p.getSalePrice(),
                p.getDescription(), p.getMaterial(), p.getOrigin(),
                p.getImages().stream().map(i -> imageUrls.of(i.getS3Key())).toList(),
                p.getOption1Name(), p.getOption2Name(),
                liveOptions.stream()
                        .map(o -> new OptionItem(o.getId(), o.getOption1Value(), o.getOption2Value(), o.getExtraPrice(), o.getStock() == 0))
                        .toList(),
                purchasable,
                liveOptions.stream().allMatch(o -> o.getStock() == 0),
                p.getCreatedAt().atZone(KST).toOffsetDateTime());
    }
}
