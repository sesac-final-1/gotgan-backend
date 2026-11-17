package com.sesac.gojigi.admin.system.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 장애 분석 리포트 응답 (11/17, docs/api/admin.md 10절) — 시각은 epoch ms, 건수는 정수(퍼센트 없음).
 * 운영팀 섹션 = summary · buckets · issues · events, 개발자 섹션 = 각 이슈의 causes · logs + suspicious
 */
public final class IncidentReportResponses {

    private IncidentReportResponses() {
    }

    public record Report(OffsetDateTime generatedAt, long fromMs, long toMs, int bucketSeconds, Summary summary,
                         List<Bucket> buckets, List<Issue> issues, List<Event> events, List<MonitorResponses.ErrorItem> suspicious) {
    }

    /** headline = 운영팀이 읽는 한 문장. issues = 이슈 수(그중 intendedIssues 는 관리자가 일부러 낸 시험) */
    public record Summary(String headline, int issues, int intendedIssues, long requests, long serverErrors, long suspicious,
                          long rateLimited) {
    }

    /** 시간 구간 하나 [t, t + bucketSeconds) — byService = 서비스별 5xx 건수(0 은 뺌) */
    public record Bucket(long t, long requests, long serverErrors, Map<String, Long> byService, long rateLimited, long notFound,
                         long unauthorized, long suspicious, long dbErrors, long errorLogs) {
    }

    /**
     * 이슈 = 이상 신호가 이어진 구간 하나. kind: ERROR(응답 실패 · DB 오류 · 차단기) / ATTACK(탐색 · 공격 의심) / TRAFFIC(요청 급증 · 제한 초과).
     * sentence = 운영팀용 문장, causes = 원인 후보(앞이 주 원인), logs = 개발자용 근거 로그(traceId 포함)
     */
    public record Issue(int no, String kind, long startMs, long endMs, List<String> services, long serverErrors, long suspicious,
                        long rateLimited, boolean intended, String sentence, List<Cause> causes, List<MonitorResponses.ErrorItem> logs) {
    }

    /** code: CHAOS · LOAD_TEST · POD_RESTART · OOM · POD_DOWN · DEPLOY · DB · SURGE · RATE_LIMIT · ATTACK · CIRCUIT · UNKNOWN */
    public record Cause(String code, String label, String detail) {
    }

    /** type: CHAOS_KILL · CHAOS_RECOVER · POD_START · POD_STOP · POD_RESTART · DEPLOY · LOADTEST_START · LOADTEST_END · CIRCUIT_OPEN */
    public record Event(long t, String type, String service, String detail) {
    }
}
