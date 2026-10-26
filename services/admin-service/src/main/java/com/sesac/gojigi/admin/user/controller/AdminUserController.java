package com.sesac.gojigi.admin.user.controller;

import com.sesac.gojigi.admin.user.client.UserClientAdapter;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/**
 * 관리자 회원 관리 (USR-09 · 10) — user-service /internal/users 응답 · 오류를 그대로 전달 (docs: 시트 Api 명세서 admin 행).
 * 역할(ADMIN)은 게이트웨이가 확인하고 X-User-Id 로 관리자를 알려 준다
 */
@Tag(name = "관리자 회원", description = "user-service 내부 API 를 그대로 전달 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserClientAdapter userClientAdapter;

    @Operation(summary = "회원 목록", description = "q(이름 · 이메일) · status(ACTIVE/SUSPENDED) · page(0부터) · size(1~100) → PageResponse")
    @GetMapping
    public ApiResponse<JsonNode> list(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                      @RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success("회원 목록을 조회했습니다.", userClientAdapter.list(admin.userId(), q, status, page, size));
    }

    @Operation(summary = "회원 상세", description = "목록 항목 + phone · statusReason · suspendedAt · suspendedUntil. 404 USER_NOT_FOUND")
    @GetMapping("/{userId}")
    public ApiResponse<JsonNode> detail(@Parameter(hidden = true) @CurrentUser AuthUser admin, @PathVariable Long userId) {
        return ApiResponse.success("회원 상세를 조회했습니다.", userClientAdapter.detail(admin.userId(), userId));
    }

    @Operation(summary = "회원 포인트 잔액 · 이력", description = "page · size → {balance, history: PageResponse}")
    @GetMapping("/{userId}/points")
    public ApiResponse<JsonNode> points(@Parameter(hidden = true) @CurrentUser AuthUser admin, @PathVariable Long userId,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success("회원 포인트를 조회했습니다.", userClientAdapter.points(admin.userId(), userId, page, size));
    }

    @Operation(summary = "이용정지 · 해제", description = """
            {status: SUSPENDED/ACTIVE, reason(필수), days?(1~365, 생략 = 영구)} → 회원 상세(suspendedUntil 포함).
            에러 그대로: 400 USER_STATUS_REASON_REQUIRED · INVALID_INPUT / 404 USER_NOT_FOUND / 409 USER_INVALID_STATUS_TRANSITION""")
    @PatchMapping("/{userId}/status")
    public ApiResponse<JsonNode> changeStatus(@Parameter(hidden = true) @CurrentUser AuthUser admin, @PathVariable Long userId,
                                              @RequestBody JsonNode body) {
        return ApiResponse.success("회원 상태를 변경했습니다.", userClientAdapter.changeStatus(admin.userId(), userId, body));
    }
}
