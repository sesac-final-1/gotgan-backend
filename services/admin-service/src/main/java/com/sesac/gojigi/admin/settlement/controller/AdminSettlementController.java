package com.sesac.gojigi.admin.settlement.controller;

import com.sesac.gojigi.admin.settlement.client.SettlementClientAdapter;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 관리자 정산 · 수수료 (STL-01 · 03 · 05 · 06 · 09) — settlement-service 내부 API 응답 · 오류를 그대로 전달 (docs/api/settlement.md).
 * 역할(ADMIN)은 게이트웨이가 확인한다
 */
@Tag(name = "관리자 정산 · 수수료", description = "settlement-service 내부 API 를 그대로 전달 — 인가: ADMIN")
@RestController
@RequiredArgsConstructor
public class AdminSettlementController {

    private final SettlementClientAdapter settlementClientAdapter;

    @Operation(summary = "주차별 정산 목록 + 합계", description = "week(ISO 2026-W43, 생략 = 지난주) · storeId · status(SCHEDULED/HELD/PAID)")
    @GetMapping("/api/admin/settlements")
    public ApiResponse<JsonNode> list(@RequestParam(required = false) String week, @RequestParam(required = false) Long storeId,
                                      @RequestParam(required = false) String status) {
        return ApiResponse.success("정산 목록을 조회했습니다.", settlementClientAdapter.list(week, storeId, status));
    }

    @Operation(summary = "정산 상세", description = "건별 상품주문 · 수수료율 · 지급 계좌 · 상태 이력. 404 SETTLE_NOT_FOUND")
    @GetMapping("/api/admin/settlements/{id}")
    public ApiResponse<JsonNode> detail(@PathVariable Long id) {
        return ApiResponse.success("정산 상세를 조회했습니다.", settlementClientAdapter.detail(id));
    }

    @Operation(summary = "지금 집계", description = "{week?}(생략 = 지난주) — 멱등(스토어 × 주차 1건)")
    @PostMapping("/api/admin/settlements/aggregate")
    public ApiResponse<JsonNode> aggregate(@RequestBody(required = false) JsonNode body) {
        return ApiResponse.success("정산을 집계했습니다.", settlementClientAdapter.aggregate(body));
    }

    @Operation(summary = "지급 실행 (단건 · 일괄)", description = "{ids[]} → {success[], failed[{id, code, message}]}")
    @PostMapping("/api/admin/settlements/pay")
    public ApiResponse<JsonNode> pay(@RequestBody JsonNode body) {
        return ApiResponse.success("지급을 실행했습니다.", settlementClientAdapter.pay(body));
    }

    @Operation(summary = "지급 보류 · 해제", description = "{hold, reason(hold=true 면 필수)} → 상세. 400 SETTLE_HOLD_REASON_REQUIRED · 409 SETTLE_INVALID_STATUS")
    @PatchMapping("/api/admin/settlements/{id}/hold")
    public ApiResponse<JsonNode> hold(@PathVariable Long id, @RequestBody JsonNode body) {
        return ApiResponse.success("지급 보류 상태를 변경했습니다.", settlementClientAdapter.hold(id, body));
    }

    @Operation(summary = "카테고리 수수료 조회", description = "[{categoryId, current{rate, effectiveFrom} | null, reserved[]}]")
    @GetMapping("/api/admin/commissions")
    public ApiResponse<JsonNode> commissions() {
        return ApiResponse.success("수수료를 조회했습니다.", settlementClientAdapter.commissions());
    }

    @Operation(summary = "카테고리 수수료 변경(예약)", description = "[{categoryId, rate(%), effectiveFrom?}] — 생략 = 다음 주 월요일. 지난 날짜 400 COMMISSION_PAST_EFFECTIVE_DATE")
    @PutMapping("/api/admin/commissions")
    public ApiResponse<JsonNode> updateCommissions(@RequestBody JsonNode body) {
        return ApiResponse.success("수수료를 변경했습니다.", settlementClientAdapter.updateCommissions(body));
    }
}
