package com.sesac.gojigi.user.user.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.user.user.dto.request.ChangeUserStatusRequest;
import com.sesac.gojigi.user.user.dto.response.AdminUserDetailResponse;
import com.sesac.gojigi.user.user.dto.response.AdminUserSummaryResponse;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.service.AdminUserService;
import com.sesac.gojigi.user.user.service.UserStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final UserStatusService userStatusService;

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
            목록 항목 + 연락처 · 상태 사유(statusReason, 마지막 정지 · 해제 사유) · 정지 시각 · 정지 종료 시각(null = 영구).
            에러: 404 USER_NOT_FOUND(없는 회원 · 탈퇴)""")
    @GetMapping("/{userId}")
    public ApiResponse<AdminUserDetailResponse> detail(@PathVariable Long userId) {
        return ApiResponse.success("회원 정보를 조회했습니다.", adminUserService.getDetail(userId));
    }

    @Operation(summary = "이용정지 · 해제", description = """
            status=SUSPENDED: 정지 — days(1~365) 일 뒤 자동 해제, 생략하면 영구. Keycloak 계정 비활성 + 모든 세션 종료(Refresh Token 전부 무효)
            → 재로그인 · 재발급 불가, 남은 Access Token 은 gateway 차단 목록으로 401.
            status=ACTIVE: 해제 — Keycloak 계정 활성 · 차단 목록 삭제 (days 무시). 사유는 둘 다 필수. 응답은 바뀐 회원 상세.
            X-User-Id(선택) = 처리한 관리자, 로그용.
            에러: 400 USER_STATUS_REASON_REQUIRED · 400 INVALID_INPUT(status 없음 · days 범위 · 사유 255자 초과)
            · 404 USER_NOT_FOUND(없는 회원 · 탈퇴) · 409 USER_INVALID_STATUS_TRANSITION(이미 정지 · 이미 정상)""")
    @PatchMapping("/{userId}/status")
    public ApiResponse<AdminUserDetailResponse> changeStatus(@PathVariable Long userId,
            @Valid @RequestBody ChangeUserStatusRequest request,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) Long adminId) {
        AdminUserDetailResponse response = userStatusService.changeStatus(userId, request, adminId);
        return ApiResponse.success(request.status() == UserStatus.SUSPENDED
                ? "회원을 이용정지했습니다." : "회원 이용정지를 해제했습니다.", response);
    }
}
