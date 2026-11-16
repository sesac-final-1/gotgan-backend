package com.sesac.gojigi.admin.system.dto.request;

import com.sesac.gojigi.admin.system.entity.LoadTestScenario;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 관리자 화면 부하테스트 요청 (docs/api/admin.md 8절) */
public final class LoadTestRequests {

    private LoadTestRequests() {
    }

    /** 시작 — 처음 사용자 수(1 ~ 300), 최대 시간 30초 ~ 10분 */
    public record Start(
            @NotNull LoadTestScenario scenario,
            @Min(1) @Max(300) int vus,
            @Min(30) @Max(600) int durationSec) {
    }

    /** 실행 중 사용자 수 변경 — 0 이면 요청을 멈추고 대기 */
    public record Scale(@Min(0) @Max(300) int vus) {
    }
}
