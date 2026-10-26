package com.sesac.gojigi.admin.application.controller;

import com.sesac.gojigi.admin.application.service.AdminApplicationService;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 관리자 입점 심사 — store /internal/applications (docs/api/store.md 3~7절) 응답 · 오류 그대로 + categoryName */
@Tag(name = "관리자 입점 심사", description = "store 내부 API 를 그대로 전달 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/applications")
@RequiredArgsConstructor
public class AdminApplicationController {

    private final AdminApplicationService adminApplicationService;

    @Operation(summary = "입점 신청 목록", description = "status(PENDING/SUPPLEMENT/APPROVED/REJECTED, 생략 = 전체) → 배열(페이징 없음, 제출 최신순) + categoryName")
    @GetMapping
    public ApiResponse<JsonNode> list(@RequestParam(required = false) String status) {
        return ApiResponse.success("입점 신청 목록을 조회했습니다.", adminApplicationService.list(status));
    }

    @Operation(summary = "입점 신청 상세", description = "신청서 · 서류(presigned GET 10분) · 이력 · 같은 사업자번호 신청/스토어 + categoryName. 404 APPLY_NOT_FOUND")
    @GetMapping("/{applicationId}")
    public ApiResponse<JsonNode> detail(@PathVariable Long applicationId) {
        return ApiResponse.success("입점 신청 상세를 조회했습니다.", adminApplicationService.detail(applicationId));
    }

    @Operation(summary = "입점 승인", description = "본문 없음 → {applicationId, status, storeId, decidedAt}. 409 APPLY_INVALID_STATUS · APPLY_STORE_ALREADY_EXISTS · APPLY_SLUG_DUPLICATED · APPLY_STORE_NAME_DUPLICATED")
    @PatchMapping("/{applicationId}/approve")
    public ApiResponse<JsonNode> approve(@PathVariable Long applicationId) {
        return ApiResponse.success("입점을 승인했습니다.", adminApplicationService.approve(applicationId));
    }

    @Operation(summary = "입점 반려", description = "{reason(필수, 255자)} → {applicationId, status, decidedAt}. 409 APPLY_INVALID_STATUS")
    @PatchMapping("/{applicationId}/reject")
    public ApiResponse<JsonNode> reject(@PathVariable Long applicationId, @RequestBody JsonNode body) {
        return ApiResponse.success("입점을 반려했습니다.", adminApplicationService.reject(applicationId, body));
    }

    @Operation(summary = "입점 보완요청", description = "{message(필수), requestedDocs[](BUSINESS_REG · ECOMMERCE_REG · BANK_COPY · PRODUCT_PHOTO)} → {applicationId, status, dueAt(+7일)}")
    @PatchMapping("/{applicationId}/supplement")
    public ApiResponse<JsonNode> supplement(@PathVariable Long applicationId, @RequestBody JsonNode body) {
        return ApiResponse.success("보완을 요청했습니다.", adminApplicationService.supplement(applicationId, body));
    }
}
