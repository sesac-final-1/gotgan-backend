package com.sesac.gojigi.admin.review.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import tools.jackson.databind.JsonNode;

/** review-service 내부 API (10/26 확인 요청 4-2). 직접 쓰지 않고 ReviewClientAdapter 를 거친다 */
@HttpExchange("/internal/reviews")
public interface ReviewClient {

    @GetExchange("/{reviewId}")
    ApiResponse<JsonNode> get(@PathVariable Long reviewId);

    @PatchExchange("/{reviewId}/blind")
    ApiResponse<JsonNode> blind(@PathVariable Long reviewId, @RequestBody JsonNode body);
}
