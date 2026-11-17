package com.sesac.gojigi.admin.system.controller;

import com.sesac.gojigi.admin.system.dto.response.MonitorResponses;
import com.sesac.gojigi.admin.system.service.MonitorService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 화면 실시간 모니터링 (11/16) — 운영 지표 · 요청 · 로그를 보여 주므로 게이트웨이 ADMIN 확인에 더해 여기서도 확인한다 */
@Tag(name = "관리자 실시간 모니터링", description = "총 현황 · 서비스별 그래프, 호출 · 장애 목록, 트레이스, 분석 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/system/monitor")
@RequiredArgsConstructor
public class AdminMonitorController {

    private static final String ADMIN = "ADMIN";
    /** Zipkin traceId — 16 · 32자리 16진수. 경로에 그대로 넘기므로 다른 문자는 400 */
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{16,32}");

    private final MonitorService monitorService;

    @Operation(summary = "총 현황 · 서비스별 그래프 (2초 폴링)", description = """
            게이트웨이 라우트 지표(실제 트래픽 포함) 최근 3분 5초 간격 — rps · avgMs · errorRate(5xx ÷ 전체) [t ms, v],
            서비스별 db = DB 연결 풀(active · pending · max, 없으면 null). Prometheus 실패면 빈 목록""")
    @GetMapping("/overview")
    public ApiResponse<MonitorResponses.Overview> overview(@Parameter(hidden = true) @CurrentUser AuthUser admin) {
        requireAdmin(admin);
        return ApiResponse.success("모니터링 현황을 조회했습니다.", monitorService.overview());
    }

    @Operation(summary = "최근 호출 (1초 폴링)", description = "게이트웨이를 지난 최근 1분 요청 최신순 40건(Zipkin). 관리자 시스템 화면 폴링 · 액추에이터는 뺀다. ?service=product-service 면 그 서비스만(라우트 없는 이름 400)")
    @GetMapping("/calls")
    public ApiResponse<List<MonitorResponses.Call>> calls(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                                          @RequestParam(required = false) String service) {
        requireAdmin(admin);
        requireFilterable(service);
        return ApiResponse.success("최근 호출을 조회했습니다.", monitorService.calls(service));
    }

    @Operation(summary = "최근 장애 (1초 폴링)", description = "최근 10분 — RESPONSE(게이트웨이 5xx 응답, Zipkin) · LOG(서비스 WARN · ERROR 로그, Loki) 최신순 40건. ?service= 로 그 서비스만")
    @GetMapping("/errors")
    public ApiResponse<List<MonitorResponses.ErrorItem>> errors(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                                                @RequestParam(required = false) String service) {
        requireAdmin(admin);
        requireFilterable(service);
        return ApiResponse.success("최근 장애를 조회했습니다.", monitorService.errors(service));
    }

    @Operation(summary = "트레이스", description = "구간을 시작 시각순으로(서비스 · 이름 · 시작 오프셋 · 소요 시간 · 상태). 없으면 spans 빈 목록")
    @GetMapping("/traces/{traceId}")
    public ApiResponse<MonitorResponses.Trace> trace(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                                     @PathVariable String traceId) {
        requireAdmin(admin);
        if (!TRACE_ID.matcher(traceId).matches()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return ApiResponse.success("트레이스를 조회했습니다.", monitorService.trace(traceId));
    }

    @Operation(summary = "분석 — 최근 5분 (10초 폴링)", description = "topErrors(5xx 많은 API TOP 5) · slowest(평균 응답 느린 API TOP 5) · statuses(게이트웨이 상태 코드 분포)")
    @GetMapping("/summary")
    public ApiResponse<MonitorResponses.Summary> summary(@Parameter(hidden = true) @CurrentUser AuthUser admin) {
        requireAdmin(admin);
        return ApiResponse.success("모니터링 분석을 조회했습니다.", monitorService.summary());
    }

    /** 서비스 필터 — 게이트웨이 라우트가 있는 서비스 이름만(Loki · Zipkin 조회문에 들어가므로 다른 값은 400) */
    private static void requireFilterable(String service) {
        if (!MonitorService.isFilterable(service)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private static void requireAdmin(AuthUser user) {
        if (!user.hasRole(ADMIN)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
