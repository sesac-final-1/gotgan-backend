package com.sesac.gojigi.admin.system.controller;

import com.sesac.gojigi.admin.system.dto.request.LoadTestRequests;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses;
import com.sesac.gojigi.admin.system.service.LoadTestService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 화면 부하테스트 (11/16) — 클러스터에 Job 을 만드는 API 라 게이트웨이 ADMIN 확인에 더해 여기서도 확인한다 */
@Tag(name = "관리자 부하테스트", description = "k6 부하 시작, 사용자 수 변경, 중지, 그래프, 부하용 계정 준비 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/system/loadtest")
@RequiredArgsConstructor
public class AdminLoadTestController {

    private static final String ADMIN = "ADMIN";

    private final LoadTestService loadTestService;

    @Operation(summary = "상태, 그래프 (실행 중 1초 폴링)", description = """
            load, accounts = 가장 최근 실행(state RUNNING/DONE/FAILED, 없으면 null). series = load 의 최근 5분 1초 간격
            vus, rps, p95Ms, errorRate([t ms, v]). Prometheus 실패면 빈 목록, 쿠버네티스 조회 실패 503 SYSTEM_CLUSTER_UNAVAILABLE""")
    @GetMapping
    public ApiResponse<LoadTestResponses.Status> status(@Parameter(hidden = true) @CurrentUser AuthUser admin) {
        requireAdmin(admin);
        return ApiResponse.success("부하테스트 상태를 조회했습니다.", loadTestService.status());
    }

    @Operation(summary = "부하 시작", description = """
            {scenario: BROWSE/REVIEWS/CART/MIXED, vus 1 ~ 300, durationSec 30 ~ 600} → 202 {id}. CART, MIXED 는 「계정 준비」 뒤에.
            403 SYSTEM_LOADTEST_DISABLED, 409 SYSTEM_LOADTEST_RUNNING, 503 SYSTEM_CLUSTER_UNAVAILABLE""")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<LoadTestResponses.Started> start(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                                        @Valid @RequestBody LoadTestRequests.Start request) {
        requireAdmin(admin);
        return ApiResponse.success("부하테스트를 시작했습니다.", loadTestService.start(request));
    }

    @Operation(summary = "실행 중 사용자 수 변경", description = """
            {vus 0 ~ 300} — 0 이면 요청을 멈추고 대기. 409 SYSTEM_LOADTEST_NOT_RUNNING(없음, 파드 준비 중), 503 SYSTEM_LOADTEST_CONTROL_FAILED""")
    @PatchMapping
    public ApiResponse<Void> scale(@Parameter(hidden = true) @CurrentUser AuthUser admin, @Valid @RequestBody LoadTestRequests.Scale request) {
        requireAdmin(admin);
        loadTestService.scale(request);
        return ApiResponse.success("사용자 수를 바꿨습니다.", null);
    }

    @Operation(summary = "중지", description = "실행 중인 Job(부하, 계정 준비) 삭제. 없으면 그대로 200")
    @DeleteMapping
    public ApiResponse<Void> stop(@Parameter(hidden = true) @CurrentUser AuthUser admin) {
        requireAdmin(admin);
        loadTestService.stop();
        return ApiResponse.success("부하테스트를 중지했습니다.", null);
    }

    @Operation(summary = "부하용 계정 준비", description = """
            loadtest+1 ~ 300@gotgan.test 가입(이미 있으면 그대로, 약 1분) → 202 {id}. 매일 08:00 DB 가 새로 만들어져 시연 날 아침 한 번.
            403 SYSTEM_LOADTEST_DISABLED, 409 SYSTEM_LOADTEST_RUNNING""")
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<LoadTestResponses.Started> prepareAccounts(@Parameter(hidden = true) @CurrentUser AuthUser admin) {
        requireAdmin(admin);
        return ApiResponse.success("부하용 계정 준비를 시작했습니다.", loadTestService.prepareAccounts());
    }

    private static void requireAdmin(AuthUser user) {
        if (!user.hasRole(ADMIN)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
