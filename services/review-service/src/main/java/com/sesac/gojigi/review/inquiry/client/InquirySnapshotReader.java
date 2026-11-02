package com.sesac.gojigi.review.inquiry.client;

import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 문의 등록 스냅샷 — 스토어(판매자 문의 목록 키) · 상품명 · 대표 사진은 product, 작성자 표시 이름은 user 에서 읽는다.
 * storeId 는 요청을 믿지 않고 상품에서 가져온다. 호출 · 서킷 · 재시도 · 실패 변환은 어댑터가 한다
 * (없는 · 삭제된 상품 INQUIRY_PRODUCT_NOT_FOUND, 장애 · 서킷 OPEN 502). 상품이 없으면 회원은 조회하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class InquirySnapshotReader {

    private final ProductClientAdapter productClient;
    private final UserClientAdapter userClient;

    public InquirySnapshot read(Long userId, Long productId) {
        ProductSummaryResponse product = productClient.getProduct(productId);
        return new InquirySnapshot(product.storeId(), product.name(), product.thumbnailUrl(),
                userClient.getUser(userId).displayName());
    }

    /** 문의에 함께 저장할 값 */
    public record InquirySnapshot(Long storeId, String productName, String thumbnailUrl, String writerDisplayName) {
    }
}
