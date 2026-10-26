package com.sesac.gojigi.admin.category.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import tools.jackson.databind.JsonNode;

/** product-service 카테고리 관리 내부 API (docs/api/product.md 7절, backend #90). 직접 쓰지 않고 ProductClientAdapter 를 거친다 */
@HttpExchange("/internal/categories")
public interface ProductClient {

    @GetExchange
    ApiResponse<JsonNode> categories();

    @PostExchange
    ApiResponse<JsonNode> create(@RequestBody JsonNode body);

    @PutExchange("/{id}")
    ApiResponse<JsonNode> update(@PathVariable Long id, @RequestBody JsonNode body);

    @PatchExchange("/{id}")
    ApiResponse<JsonNode> changeActive(@PathVariable Long id, @RequestBody JsonNode body);
}
