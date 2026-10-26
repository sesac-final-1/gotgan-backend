package com.sesac.gojigi.admin.store.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import tools.jackson.databind.JsonNode;

/**
 * store-service 관리자 내부 API (docs/api/store.md 1 · 3~9절, #97 계약 — 구현은 store 3 · 4주차). 목록은 페이징 없는 배열.
 * 직접 쓰지 않고 StoreClientAdapter 를 거친다
 */
@HttpExchange("/internal")
public interface StoreClient {

    @GetExchange("/applications")
    ApiResponse<JsonNode> applications(@RequestParam(required = false) String status);

    @GetExchange("/applications/{applicationId}")
    ApiResponse<JsonNode> application(@PathVariable Long applicationId);

    @PatchExchange("/applications/{applicationId}/approve")
    ApiResponse<JsonNode> approve(@PathVariable Long applicationId);

    @PatchExchange("/applications/{applicationId}/reject")
    ApiResponse<JsonNode> reject(@PathVariable Long applicationId, @RequestBody JsonNode body);

    @PatchExchange("/applications/{applicationId}/supplement")
    ApiResponse<JsonNode> supplement(@PathVariable Long applicationId, @RequestBody JsonNode body);

    @GetExchange("/stores")
    ApiResponse<JsonNode> stores(@RequestParam(required = false) String status);

    @GetExchange("/stores/{storeId}")
    ApiResponse<JsonNode> store(@PathVariable Long storeId);

    @PatchExchange("/stores/{storeId}/status")
    ApiResponse<JsonNode> changeStoreStatus(@PathVariable Long storeId, @RequestBody JsonNode body);
}
