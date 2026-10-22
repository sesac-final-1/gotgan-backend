package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.order.order.client.dto.StoreResponse;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** store-service 호출 — 서킷 브레이커 "store", 조회(GET)라 재시도 1회. 실패 변환 규칙은 RemoteCalls */
@Component
@RequiredArgsConstructor
public class StoreClientAdapter {

    private static final String CIRCUIT = "store";
    private static final String TARGET = "store";

    private final StoreClient storeClient;

    /** 상품주문 스냅샷용 스토어 조회 — 없는 스토어의 상품은 구매 불가로 본다 */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "getStoreFallback")
    public StoreResponse getStore(Long storeId) {
        try {
            return storeClient.getStore(storeId).data();
        } catch (HttpClientErrorException.NotFound e) {
            throw new OrderException(OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "스토어 조회", e);
        }
    }

    StoreResponse getStoreFallback(Long storeId, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "스토어 조회", t);
    }
}
