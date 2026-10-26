package com.sesac.gojigi.admin.dashboard.service;

import com.sesac.gojigi.admin.dashboard.client.OrderClientAdapter;
import com.sesac.gojigi.admin.settlement.client.SettlementClientAdapter;
import com.sesac.gojigi.common.web.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 관리자 대시보드 (ADM-02) — 서비스별 통계를 모아 한 번에. 한 곳이 실패해도 200 + 그 영역 null + unavailable[] (부분 응답, 컨벤션 공통 규칙).
 * 지금은 내부 통계 API 가 있는 settlement · order 만 — store(입점 심사 대기) · product(판매중 · 품절) · user 통계는 그 API 가 생기면 추가
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final SettlementClientAdapter settlementClientAdapter;
    private final OrderClientAdapter orderClientAdapter;

    public Dashboard dashboard() {
        List<String> unavailable = new ArrayList<>();
        JsonNode settlement = section("settlement", settlementClientAdapter::platformStats, unavailable);
        JsonNode order = section("order", orderClientAdapter::platformStats, unavailable);
        return new Dashboard(settlement, order, unavailable);
    }

    private static JsonNode section(String name, Supplier<JsonNode> call, List<String> unavailable) {
        try {
            return call.get();
        } catch (BusinessException e) {
            log.warn("대시보드 {} 영역 비움 - code={}", name, e.getErrorCode().getCode());
            unavailable.add(name);
            return null;
        }
    }

    /** settlement = /internal/stats/settlements 의 platform, order = /internal/stats/orders(전체) 그대로 */
    public record Dashboard(JsonNode settlement, JsonNode order, List<String> unavailable) {
    }
}
