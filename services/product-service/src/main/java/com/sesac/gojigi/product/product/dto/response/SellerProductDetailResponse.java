package com.sesac.gojigi.product.product.dto.response;

import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.product.entity.HiddenReason;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 판매자 상품 상세 (product.md 12절) — 등록 · 수정 응답과 수정 화면 조회가 같이 쓴다.
 * 공개 상세(10절)에 판매자만 보는 값(status · statusReason · hiddenReason · 옵션 stock · 이미지 key)을 더한 모양.
 */
public record SellerProductDetailResponse(
        Long productId, Long storeId,
        Long categoryId, String categoryName, Long topCategoryId, String topCategoryName,
        String name, long price, BigDecimal discountRate, long salePrice,
        String description, String material, String origin,
        List<ImageItem> images,
        String option1Name, String option2Name,
        List<OptionItem> options,
        boolean purchasable, boolean soldOut,
        ProductStatus status, String statusReason, HiddenReason hiddenReason,
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record ImageItem(String key, String url) {
    }

    public record OptionItem(Long productOptionId, String option1Value, String option2Value,
                             long extraPrice, int stock, boolean soldOut) {
    }

    /** category = 하위, top = 그 상위 */
    public static SellerProductDetailResponse of(Product p, Category category, Category top, ImageUrls imageUrls) {
        List<ProductOption> liveOptions = p.getOptions().stream().filter(o -> o.getDeletedAt() == null).toList();
        HiddenReason hiddenReason = HiddenReason.of(p, category.isActive() && top.isActive());
        return new SellerProductDetailResponse(
                p.getId(), p.getStoreId(),
                category.getId(), category.getName(), top.getId(), top.getName(),
                p.getName(), p.getPrice(), p.getDiscountRate(), p.getSalePrice(),
                p.getDescription(), p.getMaterial(), p.getOrigin(),
                p.getImages().stream().map(i -> new ImageItem(i.getS3Key(), imageUrls.of(i.getS3Key()))).toList(),
                p.getOption1Name(), p.getOption2Name(),
                liveOptions.stream().map(SellerProductDetailResponse::option).toList(),
                hiddenReason == null && p.getDeletedAt() == null,
                liveOptions.stream().allMatch(o -> o.getStock() == 0),
                p.getStatus(), p.getStatusReason(), hiddenReason,
                p.getCreatedAt().atZone(KST).toOffsetDateTime());
    }

    private static OptionItem option(ProductOption o) {
        return new OptionItem(o.getId(), o.getOption1Value(), o.getOption2Value(), o.getExtraPrice(), o.getStock(), o.getStock() == 0);
    }
}
