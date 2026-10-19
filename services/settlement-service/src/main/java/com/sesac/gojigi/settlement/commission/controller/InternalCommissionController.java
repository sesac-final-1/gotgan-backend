package com.sesac.gojigi.settlement.commission.controller;

import com.sesac.gojigi.settlement.commission.dto.request.CommissionUpdateRequest;
import com.sesac.gojigi.settlement.commission.dto.response.CommissionResponse;
import com.sesac.gojigi.settlement.commission.service.CommissionService;
import com.sesac.gojigi.settlement.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 카테고리 판매수수료 (STL-01) — admin-service 의 /api/admin/commissions 가 호출 */
@Tag(name = "수수료 (내부)", description = "admin-service 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/commissions")
@RequiredArgsConstructor
public class InternalCommissionController {

    private final CommissionService commissionService;

    @Operation(summary = "카테고리 수수료 조회", description = "카테고리별 현재 적용분 + 예약분")
    @GetMapping
    public ApiResponse<List<CommissionResponse>> list() {
        return ApiResponse.success("수수료를 조회했습니다.", commissionService.list());
    }

    @Operation(summary = "카테고리 수수료 변경(예약)", description = "rate %(11.5), effectiveFrom 생략 = 다음 주 월요일 · 지난 날짜 400. 이력 보존")
    @PutMapping
    public ApiResponse<List<CommissionResponse>> update(@RequestBody @NotEmpty List<@Valid CommissionUpdateRequest> requests) {
        return ApiResponse.success("수수료를 변경했습니다.", commissionService.update(requests));
    }
}
