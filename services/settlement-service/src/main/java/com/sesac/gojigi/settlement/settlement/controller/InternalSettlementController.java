package com.sesac.gojigi.settlement.settlement.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.settlement.settlement.dto.request.AggregateRequest;
import com.sesac.gojigi.settlement.settlement.dto.request.HoldRequest;
import com.sesac.gojigi.settlement.settlement.dto.request.PayRequest;
import com.sesac.gojigi.settlement.settlement.dto.response.PayResultResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementDetailResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementListResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementOutstandingResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementStatsResponse;
import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory.Actor;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import com.sesac.gojigi.settlement.settlement.service.SettlementCommandService;
import com.sesac.gojigi.settlement.settlement.service.SettlementPayService;
import com.sesac.gojigi.settlement.settlement.service.SettlementQueryService;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * admin-service 전용 정산 내부 API — /internal/** 는 게이트웨이 라우트가 없어 외부에서 닿지 않는다.
 * ADMIN 확인은 게이트웨이(/api/admin/** hasRole ADMIN)가 하고, admin-service 가 이 API 를 부른다 (가이드라인 08장).
 */
@Tag(name = "정산 (내부)", description = "admin-service · store(대시보드) 가 호출 — 인가: 내부")
@RestController
@RequiredArgsConstructor
public class InternalSettlementController {

    private static final String ISO_WEEK = "\\d{4}-W\\d{2}";

    private final SettlementQueryService settlementQueryService;
    private final SettlementCommandService settlementCommandService;
    private final SettlementPayService settlementPayService;
    private final Clock clock;

    @Operation(summary = "주차별 정산 목록 + 합계", description = "week 생략 = 지난주. status 로 탭 필터")
    @GetMapping("/internal/settlements")
    public ApiResponse<SettlementListResponse> list(@RequestParam(required = false) @Pattern(regexp = ISO_WEEK) String week,
            @RequestParam(required = false) Long storeId, @RequestParam(required = false) SettlementStatus status) {
        return ApiResponse.success("정산 목록을 조회했습니다.", settlementQueryService.list(week(week), storeId, status));
    }

    @Operation(summary = "정산 상세", description = "건별 상품주문 · 수수료율 · 지급 계좌 · 상태 이력")
    @GetMapping("/internal/settlements/{id}")
    public ApiResponse<SettlementDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success("정산 상세를 조회했습니다.", settlementQueryService.detail(id));
    }

    @Operation(summary = "지금 집계", description = "주간 집계를 즉시 실행 — 멱등(스토어 × 주차 1건). week 생략 = 지난주")
    @PostMapping("/internal/settlements/aggregate")
    public ApiResponse<SettlementCommandService.AggregateResult> aggregate(@Valid @RequestBody(required = false) AggregateRequest request) {
        return ApiResponse.success("정산을 집계했습니다.",
                settlementCommandService.aggregate(week(request == null ? null : request.week()), Actor.ADMIN));
    }

    @Operation(summary = "지급 실행 (단건 · 일괄)", description = "지급예정 · 스토어 운영중 · 계좌 있음만 지급, 건마다 결과. 지급 후 SettlementPaid 발행")
    @PostMapping("/internal/settlements/pay")
    public ApiResponse<PayResultResponse> pay(@Valid @RequestBody PayRequest request) {
        return ApiResponse.success("지급을 실행했습니다.", settlementPayService.pay(request.ids()));
    }

    @Operation(summary = "지급 보류 · 해제", description = "hold=true 면 reason 필수. 지급완료는 409")
    @PatchMapping("/internal/settlements/{id}/hold")
    public ApiResponse<SettlementDetailResponse> hold(@PathVariable Long id, @Valid @RequestBody HoldRequest request) {
        settlementCommandService.hold(id, request.hold(), request.reason());
        return ApiResponse.success(request.hold() ? "지급을 보류했습니다." : "보류를 해제했습니다.", settlementQueryService.detail(id));
    }

    @Operation(summary = "정산 통계 (대시보드)", description = "storeId 있으면 판매자 대시보드용, 없으면 관리자 대시보드용")
    @GetMapping("/internal/stats/settlements")
    public ApiResponse<SettlementStatsResponse> stats(@RequestParam(required = false) Long storeId) {
        return ApiResponse.success("정산 통계를 조회했습니다.", settlementQueryService.stats(storeId));
    }

    @Operation(summary = "스토어 미정산 현황", description = "스토어 폐점 · 판매자 탈퇴 전 확인 — 집계 전 구매확정 · 지급예정 · 보류 정산이 모두 0 이면 clear=true")
    @GetMapping("/internal/settlements/outstanding")
    public ApiResponse<SettlementOutstandingResponse> outstanding(@RequestParam Long storeId) {
        return ApiResponse.success("미정산 현황을 조회했습니다.", settlementQueryService.outstanding(storeId));
    }

    private SettlementWeek week(String isoWeek) {
        return isoWeek == null || isoWeek.isBlank() ? SettlementWeek.lastWeek(LocalDate.now(clock)) : SettlementWeek.parse(isoWeek);
    }
}
