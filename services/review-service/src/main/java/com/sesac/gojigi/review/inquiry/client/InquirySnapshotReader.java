package com.sesac.gojigi.review.inquiry.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * 문의 등록 스냅샷 — 스토어(판매자 문의 목록 키) · 상품명 · 대표 사진은 product, 작성자 표시 이름은 user 에서 읽는다.
 * storeId 는 요청을 믿지 않고 상품에서 가져온다. 없는 · 삭제된 상품은 INQUIRY_PRODUCT_NOT_FOUND, 그 밖의 실패는 502(원인은 GlobalExceptionHandler 가 로그).
 */
@Component
@RequiredArgsConstructor
public class InquirySnapshotReader {

    private final ProductClient productClient;
    private final UserClient userClient;

    public InquirySnapshot read(Long userId, Long productId) {
        ProductSummaryResponse product = readProduct(productId);
        return new InquirySnapshot(product.storeId(), product.name(), product.thumbnailUrl(), readWriterName(userId));
    }

    private ProductSummaryResponse readProduct(Long productId) {
        try {
            return productClient.getProduct(productId).data();
        } catch (HttpClientErrorException.NotFound e) {
            throw new InquiryException(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND);
        } catch (RestClientException e) {
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "상품 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    private String readWriterName(Long userId) {
        try {
            return userClient.getUser(userId).data().displayName();
        } catch (RestClientException e) {
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "회원 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    /** 문의에 함께 저장할 값 */
    public record InquirySnapshot(Long storeId, String productName, String thumbnailUrl, String writerDisplayName) {
    }
}
