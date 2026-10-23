package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.StockReleaseRequest;
import com.sesac.gojigi.order.order.client.dto.StockReserveRequest;
import com.sesac.gojigi.order.order.client.dto.StockRestoreRequest;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * product-service 호출 — 서킷 브레이커 "product". 재시도는 조회(GET)만, 재고 예약 · 해제는 Saga 가 sagaId 로 다시 부른다.
 * 실패 변환 규칙은 RemoteCalls, 응답별 처리는 docs/api/product.md 1 · 2절.
 */
@Component
@RequiredArgsConstructor
public class ProductClientAdapter {

    private static final String CIRCUIT = "product";
    private static final String TARGET = "product";

    private final ProductClient productClient;

    /** 가격 · 구매 가능 조회 — 응답에서 빠진 옵션은 호출 쪽이 구매 불가로 처리 */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "getOptionsFallback")
    public List<ProductOptionResponse> getOptions(List<Long> optionIds) {
        try {
            return productClient.getOptions(optionIds).data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "옵션 조회", e);
        }
    }

    /** 재고 예약 (Saga STOCK_RESERVED) — 재고 부족 · 구매 불가 · 없는 옵션은 주문 예외로 */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "reserveFallback")
    public void reserve(StockReserveRequest request) {
        try {
            productClient.reserve(request);
        } catch (HttpClientErrorException e) {
            String code = RemoteCalls.errorCode(e);
            if ("STOCK_INSUFFICIENT".equals(code)) {
                throw new OrderException(OrderErrorCode.ORDER_OUT_OF_STOCK);
            }
            if ("PRODUCT_NOT_PURCHASABLE".equals(code) || e instanceof HttpClientErrorException.NotFound) {
                throw new OrderException(OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
            }
            throw RemoteCalls.rejected(TARGET, "재고 예약", e);
        }
    }

    /** 재고 해제 (Saga 보상) — 예약이 없어도 성공 */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "releaseFallback")
    public void release(String sagaId, StockReleaseRequest request) {
        try {
            productClient.release(sagaId, request);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "재고 해제", e);
        }
    }

    /** 재고 복원 (취소 · 반품 Saga STOCK_RESTORED) — claimId 멱등, 실패하면 Saga 가 같은 claimId 로 다시 부른다 */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "restoreFallback")
    public void restore(StockRestoreRequest request) {
        try {
            productClient.restore(request);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "재고 복원", e);
        }
    }

    void restoreFallback(StockRestoreRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "재고 복원", t);
    }

    List<ProductOptionResponse> getOptionsFallback(List<Long> optionIds, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "옵션 조회", t);
    }

    void reserveFallback(StockReserveRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "재고 예약", t);
    }

    void releaseFallback(String sagaId, StockReleaseRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "재고 해제", t);
    }
}
