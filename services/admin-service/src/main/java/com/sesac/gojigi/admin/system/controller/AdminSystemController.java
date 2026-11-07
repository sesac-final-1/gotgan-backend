package com.sesac.gojigi.admin.system.controller;

import com.sesac.gojigi.admin.system.dto.request.KillRequest;
import com.sesac.gojigi.admin.system.dto.response.SystemResponses;
import com.sesac.gojigi.admin.system.service.AdminSystemService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 장애 시연 대시보드 (F-1) — 게이트웨이가 ADMIN 을 확인하지만 파드를 지우는 API 라 여기서도 한 번 더 확인한다 */
@Tag(name = "관리자 장애 시연", description = "서비스 · 사용자 기능 상태, 파드 내리기, 내리기 타임라인 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
public class AdminSystemController {

    private static final String ADMIN = "ADMIN";

    private final AdminSystemService adminSystemService;

    @Operation(summary = "서비스 · 기능 상태 (2초 폴링)", description = """
            services[] — desired · ready · state(UP/DEGRADED/DOWN/RECOVERING) · pods[], features[] — 의존 관계로 서버 계산.
            쿠버네티스 조회 실패 503 SYSTEM_CLUSTER_UNAVAILABLE""")
    @GetMapping("/status")
    public ApiResponse<SystemResponses.Status> status(@Parameter(hidden = true) @CurrentUser AuthUser admin) {
        requireAdmin(admin);
        return ApiResponse.success("시스템 상태를 조회했습니다.", adminSystemService.status());
    }

    @Operation(summary = "서비스 내리기", description = """
            {scope: ONE/ALL, reason?} → 202 {eventId, deletedPods}. 파드를 지우면 Deployment 가 새 파드를 띄운다.
            403 SYSTEM_CHAOS_DISABLED · 400 SYSTEM_SERVICE_NOT_KILLABLE · 409 SYSTEM_KILL_COOLDOWN(서비스당 1분 · 복구 중) ·
            409 SYSTEM_NO_READY_POD · 503 SYSTEM_CLUSTER_UNAVAILABLE""")
    @PostMapping("/services/{name}/kill")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SystemResponses.Killed> kill(@Parameter(hidden = true) @CurrentUser AuthUser admin, @PathVariable String name,
                                                    @Valid @RequestBody KillRequest request) {
        requireAdmin(admin);
        return ApiResponse.success("서비스를 내렸습니다.", adminSystemService.kill(name, request, admin.userId()));
    }

    @Operation(summary = "내리기 타임라인", description = "최신순 20건 — 복구 전이면 recoveredAt · recoverySeconds null")
    @GetMapping("/events")
    public ApiResponse<List<SystemResponses.EventItem>> events(@Parameter(hidden = true) @CurrentUser AuthUser admin) {
        requireAdmin(admin);
        return ApiResponse.success("내리기 기록을 조회했습니다.", adminSystemService.events());
    }

    private static void requireAdmin(AuthUser user) {
        if (!user.hasRole(ADMIN)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
