package com.sesac.gojigi.store.apply.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.service.ApplicationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
