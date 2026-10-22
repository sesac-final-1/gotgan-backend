package com.sesac.gojigi.user.user.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.user.user.dto.response.AdminUserDetailResponse;
import com.sesac.gojigi.user.user.dto.response.AdminUserSummaryResponse;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** admin-service 전용 (관리자 회원 관리) — 게이트웨이 라우트 없음, 클러스터 내부에서만 호출 */
@Tag(name = "회원 관리 (내부)", description = "admin-service 가 /api/admin/users 를 받아 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "회원 목록", description = """
            탈퇴 회원 제외, 최신 가입순 서버 페이징 (page 0부터, size 1~100 기본 20).
            q = 이름 또는 이메일 부분 일치(대소문자 무시), status = ACTIVE · SUSPENDED (생략 = 전체).
            point = 포인트 잔액, totalSpent = 누적 구매확정 금액. 에러: 400 INVALID_INPUT(page · size 범위, 없는 status)""")
    @GetMapping
    public ApiResponse<PageResponse<AdminUserSummaryResponse>> search(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success("회원 목록을 조회했습니다.", adminUserService.search(q, status, page, size));
    }

    @Operation(summary = "회원 상세", description = """
            목록 항목 + 연락처 · 상태 사유(statusReason, 마지막 정지 · 해제 사유) · 정지 시각.
            에러: 404 USER_NOT_FOUND(없는 회원 · 탈퇴)""")
    @GetMapping("/{userId}")
    public ApiResponse<AdminUserDetailResponse> detail(@PathVariable Long userId) {
        return ApiResponse.success("회원 정보를 조회했습니다.", adminUserService.getDetail(userId));
    }
}
