package com.sesac.gojigi.admin.system.dto.response;

import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import java.time.OffsetDateTime;
import java.util.List;

/** 관리자 화면 실시간 모니터링 응답 (docs/api/admin.md 9절) — 그래프 점은 [t = epoch ms, v], 목록 시각은 epoch ms */
public final class MonitorResponses {

    private MonitorResponses() {
    }

    /** GET /overview — 총 현황 + 서비스별(게이트웨이 라우트 지표, 실제 트래픽 포함). 최근 3분 5초 간격 */
    public record Overview(OffsetDateTime serverTime, Series total, List<ServiceSeries> services) {
    }

    /** rps = 초당 요청, avgMs = 평균 응답 시간(ms), errorRate = 5xx ÷ 전체(0 ~ 1). 요청이 없는 점은 0 */
    public record Series(List<Sample> rps, List<Sample> avgMs, List<Sample> errorRate) {
    }

    /** db = DB 연결 풀(파드 합) — DB 를 쓰지 않거나 조회 실패면 null */
    public record ServiceSeries(String name, Series series, Db db) {
    }

    public record Db(int active, int pending, int max) {
    }

    /** GET /calls — 게이트웨이를 지난 최근 요청(최신순). 관리자 시스템 화면 자체 폴링은 뺀다 */
    public record Call(String traceId, long timestampMs, String method, String path, String service, Integer status, double durationMs) {
    }

    /** GET /errors — RESPONSE(게이트웨이 5xx 응답) · LOG(서비스 WARN · ERROR 로그)를 최신순으로 합친 것 */
    public record ErrorItem(String kind, long timestampMs, String service, String level, String title, Integer status, String traceId) {
    }

    /** GET /traces/{traceId} — 구간을 시작 시각순으로. startMs = 트레이스 시작부터의 오프셋 */
    public record Trace(String traceId, double durationMs, List<Span> spans) {
    }

    public record Span(String id, String parentId, String service, String name, String kind, double startMs, double durationMs,
                       Integer status, boolean error) {
    }

    /** GET /summary — 최근 5분 */
    public record Summary(List<ApiCount> topErrors, List<ApiLatency> slowest, List<StatusCount> statuses) {
    }

    public record ApiCount(String service, String api, String status, long count) {
    }

    public record ApiLatency(String service, String api, double avgMs) {
    }

    public record StatusCount(String status, long count) {
    }
}
