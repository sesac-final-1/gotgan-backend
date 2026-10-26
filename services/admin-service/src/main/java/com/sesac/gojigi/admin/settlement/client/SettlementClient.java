package com.sesac.gojigi.admin.settlement.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import tools.jackson.databind.JsonNode;

/**
 * settlement-service 내부 API (docs/api/settlement.md) — 정산 · 수수료. 응답은 관리자 화면에 그대로 전달하므로 JsonNode.
 * 직접 쓰지 않고 SettlementClientAdapter 를 거친다
 */
@HttpExchange("/internal")
public interface SettlementClient {

    @GetExchange("/settlements")
    ApiResponse<JsonNode> list(@RequestParam(required = false) String week, @RequestParam(required = false) Long storeId,
                               @RequestParam(required = false) String status);

    @GetExchange("/settlements/{id}")
    ApiResponse<JsonNode> detail(@PathVariable Long id);

    @PostExchange("/settlements/aggregate")
    ApiResponse<JsonNode> aggregate(@RequestBody(required = false) JsonNode body);

    @PostExchange("/settlements/pay")
    ApiResponse<JsonNode> pay(@RequestBody JsonNode body);

    @PatchExchange("/settlements/{id}/hold")
    ApiResponse<JsonNode> hold(@PathVariable Long id, @RequestBody JsonNode body);

    @GetExchange("/commissions")
    ApiResponse<JsonNode> commissions();

    /** storeId 없이 부르면 platform 통계 (관리자 대시보드) */
    @GetExchange("/stats/settlements")
    ApiResponse<JsonNode> platformStats();

    @PutExchange("/commissions")
    ApiResponse<JsonNode> updateCommissions(@RequestBody JsonNode body);
}
