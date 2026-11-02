package com.sesac.gojigi.review.inquiry.client;

import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** product-service 호출 — 서킷 브레이커 "product", 조회(GET)라 재시도 1회. 실패 변환 규칙은 RemoteCalls */
@Component
@RequiredArgsConstructor
public class ProductClientAdapter {

    private static final String CIRCUIT = "product";
    private static final String ACTION = "상품 조회";
    private static final String MESSAGE = "상품 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private final ProductClient productClient;

    /** 문의 등록 스냅샷 — 없는 · 삭제된 상품(404)은 INQUIRY_PRODUCT_NOT_FOUND */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "getProductFallback")
    public ProductSummaryResponse getProduct(Long productId) {
        try {
            return productClient.getProduct(productId).data();
        } catch (HttpClientErrorException.NotFound e) {
            throw new InquiryException(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(CIRCUIT, ACTION, MESSAGE, e);
        }
    }

    ProductSummaryResponse getProductFallback(Long productId, Throwable t) {
        throw RemoteCalls.unavailable(CIRCUIT, ACTION, MESSAGE, t);
    }
}
