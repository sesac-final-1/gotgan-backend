package com.sesac.gojigi.admin.settlement.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/** 주간 집계 호출만 따로 — 다른 내부 호출(읽기 3초)보다 오래 걸린다. 11/5 k6: 5,000건 5.6초 → 3초 타임아웃으로 502 (AdminClientConfig) */
@HttpExchange("/internal")
public interface SettlementAggregateClient {

    @PostExchange("/settlements/aggregate")
    ApiResponse<JsonNode> aggregate(@RequestBody(required = false) JsonNode body);
}
