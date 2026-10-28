package com.sesac.gojigi.store.apply.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.apply.dto.response.ApplicationApproveResponse;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.service.ApplicationQueryService;
import com.sesac.gojigi.store.apply.service.ApplicationReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 입점 심사 — admin-service 가 /api/admin/applications/** 를 받아 그대로 호출 (docs/api/store.md 3~7절) */
@Tag(name = "입점 심사 (내부)", description = "admin-service 가 호출 — 인가: 내부 (관리자 확인은 게이트웨이 · admin 이 끝냄)")
@RestController
@RequestMapping("/internal/applications")
@RequiredArgsConstructor
public class InternalApplicationController {

    private final ApplicationQueryService applicationQueryService;
    private final ApplicationReviewService applicationReviewService;

    @Operation(summary = "입점 신청 목록",
            description = "인가: 내부. 페이징 없이 전체 배열, 제출 최신순. status 를 생략하면 전체, "
                    + "모르는 값이면 400 INVALID_INPUT. 카테고리 이름은 내려 주지 않음(categoryId 만)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공 (없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT — status 값 오류")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<InternalApplicationSummaryResponse>>> list(
            @Parameter(description = "신청 상태 필터 (PENDING · SUPPLEMENT · APPROVED · REJECTED)", example = "PENDING")
            @RequestParam(required = false) ApplicationStatus status) {
        return ResponseEntity.ok(ApiResponse.success("입점 신청 목록을 조회했습니다.", applicationQueryService.getList(status)));
    }

    @Operation(summary = "입점 신청 상세",
            description = "인가: 내부. 상태와 상관없이 신청서 전체 + 서류(이전 제출본 포함) presigned GET 10분 + 상태 이력 + "
                    + "같은 사업자번호의 다른 신청 · 스토어. 서류 주소 발급이 실패해도 200(url · urlExpiresAt null). "
                    + "없는 신청은 404 APPLY_NOT_FOUND")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT — applicationId 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "APPLY_NOT_FOUND")
    })
    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<InternalApplicationDetailResponse>> get(
            @Parameter(description = "신청 ID", example = "27") @PathVariable Long applicationId) {
        return ResponseEntity.ok(ApiResponse.success("입점 신청을 조회했습니다.", applicationQueryService.getDetail(applicationId)));
    }

    @Operation(summary = "입점 승인",
            description = "인가: 내부. 본문 없음. 심사대기 · 보완요청 신청으로 스토어(ACTIVE)를 만들고 신청을 APPROVED 로, "
                    + "StoreApproved 는 같은 트랜잭션에서 아웃박스에 남겨 커밋 직후 발행(실패해도 200 — 발행기가 다시 보냄, 최소 1회 전달). 이미 승인 · 반려됐거나 동시 처리에서 지면 409 APPLY_INVALID_STATUS, "
                    + "신청자에게 스토어가 있으면 409 APPLY_STORE_ALREADY_EXISTS, 스토어 주소 · 스토어명이 다른 스토어와 겹치면 "
                    + "409 APPLY_SLUG_DUPLICATED · APPLY_STORE_NAME_DUPLICATED, 없는 신청은 404 APPLY_NOT_FOUND. "
                    + "판매자 권한은 user 가 이벤트를 받은 뒤 다음 토큰부터 반영")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "승인 — storeId · decidedAt"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT — applicationId 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "APPLY_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "APPLY_INVALID_STATUS · APPLY_STORE_ALREADY_EXISTS · APPLY_SLUG_DUPLICATED · APPLY_STORE_NAME_DUPLICATED")
    })
    @PatchMapping("/{applicationId}/approve")
    public ResponseEntity<ApiResponse<ApplicationApproveResponse>> approve(
            @Parameter(description = "신청 ID", example = "27") @PathVariable Long applicationId) {
        return ResponseEntity.ok(ApiResponse.success("입점 신청을 승인했습니다.", applicationReviewService.approve(applicationId)));
    }
}
