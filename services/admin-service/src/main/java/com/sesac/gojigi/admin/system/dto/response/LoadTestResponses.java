package com.sesac.gojigi.admin.system.dto.response;

import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 관리자 화면 부하테스트 응답 (docs/api/admin.md 8절) — 시각은 KST 오프셋, 그래프 점은 [t = epoch ms, v] */
public final class LoadTestResponses {

    private LoadTestResponses() {
    }

    /** STOPPED = 관리자가 중지(최대 시간 전에 끝남, k6 외부 중지는 종료 코드가 0 이 아니라 Job 은 실패로 남는다) */
    public enum RunState { RUNNING, DONE, STOPPED, FAILED }

    /**
     * GET — load = 가장 최근 부하 실행, accounts = 가장 최근 계정 준비(없으면 null).
     * series = load 그래프(실행 중 최근 5분, 끝나면 그 실행 전체에서 멈춤, 1초 간격), result = load 의 누적 결과(실행 중에도 지금까지 값)
     */
    public record Status(boolean enabled, int maxVus, Run load, Run accounts, Series series, Result result) {
    }

    /** vus, durationSec 은 시작할 때 값(실행 중 변경은 series.vus 에 보인다). endsAt = 최대 시간이 끝나는 시각, finishedAt = 실제 끝난 시각(실행 중 null) */
    public record Run(String id, String scenario, RunState state, int vus, int durationSec, OffsetDateTime startedAt,
                      OffsetDateTime endsAt, OffsetDateTime finishedAt) {
    }

    /**
     * 부하테스트 결과(k6 지표, 11/17) — requests 전체 요청, failed 2xx 가 아닌 응답(연결 실패 포함), byStatus 상태 코드별 건수,
     * p95Ms 전체 응답 시간 p95, avgRps 평균 초당 요청, peakVus 최대 동시 사용자, elapsedSec 실행 시간,
     * topFailures 실패가 많은 API 5개, serverErrors 같은 시간 게이트웨이 5xx 를 서비스별로(서버 쪽 원인)
     */
    public record Result(long requests, long failed, Map<String, Long> byStatus, Double p95Ms, double avgRps, int peakVus, long elapsedSec,
                         List<Failure> topFailures, Map<String, Long> serverErrors) {
    }

    public record Failure(String api, String status, long count) {
    }

    /** GET /monitor/visitors — 최근 windowSeconds 초 안에 요청한 접속자(회원 + 비회원 IP)와 그중 로그인 회원. 부하테스트 요청은 뺀다. Redis 실패면 null */
    public record Visitors(Long visitors, Long members, int windowSeconds) {
    }

    /** vus = 동시 사용자, rps = 초당 요청, p95Ms = 응답 시간 p95(ms), errorRate = 2xx 가 아닌 비율(0 ~ 1). Prometheus 실패면 빈 목록 */
    public record Series(List<Sample> vus, List<Sample> rps, List<Sample> p95Ms, List<Sample> errorRate) {

        public static final Series EMPTY = new Series(List.of(), List.of(), List.of(), List.of());
    }

    /** POST → 202 */
    public record Started(String id) {
    }
}
