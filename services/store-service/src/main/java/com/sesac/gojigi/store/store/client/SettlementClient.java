package com.sesac.gojigi.store.store.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.store.client.dto.SettlementOutstandingResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** settlement-service 내부 API — 직접 쓰지 않고 SettlementClientAdapter(서킷 브레이커 "settlement")를 거친다 */
@HttpExchange("/internal/settlements")
public interface SettlementClient {

    /** 미정산 현황 — DB 조회만이라 재시도 안전 */
    @GetExchange("/outstanding")
    ApiResponse<SettlementOutstandingResponse> outstanding(@RequestParam Long storeId);
}
