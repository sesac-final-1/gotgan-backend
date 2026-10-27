package com.sesac.gojigi.admin.product.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import tools.jackson.databind.JsonNode;

/** product-service 관리자 상품 · 상품 통계 내부 API (docs/api/product.md 5 · 6절, backend #110). AdminProductClientAdapter 를 거친다 */
@HttpExchange("/internal")
public interface AdminProductClient {

    @GetExchange("/products")
    ApiResponse<JsonNode> products(@RequestParam Long storeId);

    @PatchExchange("/products/{productId}/block")
    ApiResponse<JsonNode> block(@PathVariable Long productId, @RequestBody JsonNode body);

    @GetExchange("/stats/products")
    ApiResponse<JsonNode> platformStats();
}
