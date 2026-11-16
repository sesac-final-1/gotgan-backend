package com.sesac.gojigi.admin.system.dto.response;

import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import java.time.OffsetDateTime;
import java.util.List;

/** 관리자 화면 부하테스트 응답 (docs/api/admin.md 8절) — 시각은 KST 오프셋, 그래프 점은 [t = epoch ms, v] */
public final class LoadTestResponses {

    private LoadTestResponses() {
    }

    public enum RunState { RUNNING, DONE, FAILED }

    /** GET — load = 가장 최근 부하 실행, accounts = 가장 최근 계정 준비(없으면 null). series 는 load 의 최근 5분(1초 간격) */
    public record Status(boolean enabled, int maxVus, Run load, Run accounts, Series series) {
    }

    /** vus · durationSec 은 시작할 때 값(실행 중 변경은 series.vus 에 보인다) */
    public record Run(String id, String scenario, RunState state, int vus, int durationSec, OffsetDateTime startedAt) {
    }

    /** vus = 동시 사용자, rps = 초당 요청, p95Ms = 응답 시간 p95(ms), errorRate = 2xx 가 아닌 비율(0 ~ 1). Prometheus 실패면 빈 목록 */
    public record Series(List<Sample> vus, List<Sample> rps, List<Sample> p95Ms, List<Sample> errorRate) {

        public static final Series EMPTY = new Series(List.of(), List.of(), List.of(), List.of());
    }

    /** POST → 202 */
    public record Started(String id) {
    }
}
