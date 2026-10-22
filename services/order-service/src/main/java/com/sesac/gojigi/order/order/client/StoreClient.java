package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.client.dto.StoreResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** store-service 내부 API (docs/api/store.md) — 직접 쓰지 않고 StoreClientAdapter(서킷 브레이커 "store")를 거친다 */
@HttpExchange("/internal/stores")
public interface StoreClient {

    /** 스토어 상세 — 상태 무관(CLOSED 포함), 없거나 소프트 삭제면 404 */
    @GetExchange("/{storeId}")
    ApiResponse<StoreResponse> getStore(@PathVariable Long storeId);
}
