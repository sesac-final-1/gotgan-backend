package com.sesac.gojigi.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sesac.gojigi.admin.system.client.LokiClient;
import com.sesac.gojigi.admin.system.client.LokiClient.LogLine;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient.RangeSeries;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Series;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Cause;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Event;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Issue;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Report;
import com.sesac.gojigi.admin.system.entity.ChaosEvent;
import com.sesac.gojigi.admin.system.entity.KillScope;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import com.sesac.gojigi.admin.system.repository.ChaosEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장애 분석 리포트 — Prometheus, Loki, 장애 주입 기록은 가짜. 11/17 실제 장애(10:50 ~ 11:30)를 본뜸:
 * 11:07 상품 전부 내리기(5xx 827건), 11:12 부하테스트 중 2건, 11:19 요청 제한 초과 + 주문 파드 사라짐, 11:24 /.env 탐색
 */
class IncidentReportServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = at("11:30");

    private final PrometheusClient prometheus = mock(PrometheusClient.class);
    private final LokiClient loki = mock(LokiClient.class);
    private final ChaosEventRepository chaos = mock(ChaosEventRepository.class);
    private final IncidentReportService service = new IncidentReportService(prometheus, loki, chaos, Clock.fixed(NOW, KST), List.of());

    private static Instant at(String hhmm) {
        return LocalDateTime.parse("2026-11-17T" + hhmm).atZone(KST).toInstant();
    }

    private static long ms(String hhmm) {
        return at(hhmm).toEpochMilli();
    }

    private static Optional<List<RangeSeries>> series(Map<String, String> labels, Sample... samples) {
        return Optional.of(List.of(new RangeSeries(labels, List.of(samples))));
    }

    private static Optional<List<Series>> vector(String label, Map<String, String> values) {
        List<Series> list = new ArrayList<>();
        values.forEach((k, hhmmss) -> list.add(new Series(Map.of(label, k),
                LocalDateTime.parse("2026-11-17T" + hhmmss).atZone(KST).toEpochSecond())));
        return Optional.of(list);
    }

    @BeforeEach
    void setUp() {
        // 5xx — 구간 끝 시각에 찍힌다: 11:07 구간 = 11:08 점
        when(prometheus.matrix(contains("httpStatusCode=~\"5..\""), any(), any(), any())).thenReturn(Optional.of(List.of(
                new RangeSeries(Map.of("routeId", "product-service"),
                        List.of(new Sample(ms("11:08"), 431.2), new Sample(ms("11:09"), 396), new Sample(ms("11:13"), 2.2))))));
        List<Sample> ok = new ArrayList<>();
        for (long t = ms("10:51"); t <= ms("11:30"); t += 60_000) {
            ok.add(new Sample(t, 100));
        }
        when(prometheus.matrix(contains("sum by (httpStatusCode)"), any(), any(), any())).thenReturn(Optional.of(List.of(
                new RangeSeries(Map.of("httpStatusCode", "200"), ok),
                new RangeSeries(Map.of("httpStatusCode", "429"), List.of(new Sample(ms("11:20"), 15))))));
        when(prometheus.matrix(contains("gateway_suspicious_requests_total"), any(), any(), any()))
                .thenReturn(series(Map.of("type", "secret-file"), new Sample(ms("11:25"), 3)));

        when(prometheus.vector(contains("last_over_time(kube_pod_created"), any(Instant.class))).thenReturn(vector("pod", Map.of(
                "product-service-757dd8467b-new", "11:06:48", "product-service-757dd8467b-old", "09:00:00",
                "order-service-775744fdf6-gone", "09:00:00", "loadtest-lt-1-abc", "11:06:15")));
        when(prometheus.vector(contains("timestamp(kube_pod_created"), any(Instant.class))).thenReturn(vector("pod", Map.of(
                "product-service-757dd8467b-new", "11:30:00", "product-service-757dd8467b-old", "11:06:46",
                "order-service-775744fdf6-gone", "11:20:00")));
        when(prometheus.vector(org.mockito.ArgumentMatchers.eq("kube_pod_created{namespace=\"sesac-gojigi\"}"), any(Instant.class)))
                .thenReturn(vector("pod", Map.of("product-service-757dd8467b-new", "11:30:00")));   // 기간 끝에 살아 있는 파드
        when(prometheus.vector(contains("min_over_time(timestamp(k6_vus)"), any(Instant.class)))
                .thenReturn(vector("testid", Map.of("lt-1", "11:06:29")));
        when(prometheus.vector(contains("max_over_time(timestamp(k6_vus)"), any(Instant.class)))
                .thenReturn(vector("testid", Map.of("lt-1", "11:15:00")));
        when(prometheus.vector(contains("max_over_time(k6_vus"), any(Instant.class)))
                .thenReturn(Optional.of(List.of(new Series(Map.of("testid", "lt-1"), 50))));

        ChaosEvent kill = ChaosEvent.of("product-service", KillScope.ALL, null, List.of("product-service-757dd8467b-old"), 1L,
                LocalDateTime.parse("2026-11-17T11:06:46"));
        kill.markRecovered(LocalDateTime.parse("2026-11-17T11:07:30"));
        when(chaos.findByKilledAtBetweenOrderByKilledAtAsc(any(), any())).thenReturn(List.of(kill));
        when(loki.lines(contains("level=~"), any(), any(), anyInt())).thenReturn(List.of(
                new LogLine(ms("11:07"), "gateway-service", "WARN", "업무 서비스 연결 실패 route=product-service", "abc123")));
        when(loki.lines(contains("의심 요청"), any(), any(), anyInt())).thenReturn(List.of(
                new LogLine(ms("11:24"), "gateway-service", "WARN", "의심 요청 type=secret-file path=/.env", null)));
    }

    @Test
    @DisplayName("이슈 묶기 — 이어진 5xx 구간은 하나, 장애 주입, 부하테스트와 겹치면 의도한 시험, 문장은 시각, 서비스, 건수, 원인")
    void errorIssues() {
        Report report = service.report(at("10:50"), at("11:30"));

        assertThat(report.bucketSeconds()).isEqualTo(60);
        assertThat(report.buckets()).hasSize(40);
        Issue first = report.issues().get(0);
        assertThat(first.kind()).isEqualTo("ERROR");
        assertThat(first.startMs()).isEqualTo(ms("11:07"));
        assertThat(first.endMs()).isEqualTo(ms("11:09"));
        assertThat(first.services()).containsExactly("product-service");
        assertThat(first.serverErrors()).isEqualTo(827);
        assertThat(first.intended()).isTrue();
        assertThat(first.causes()).extracting(Cause::code).startsWith("CHAOS", "LOAD_TEST");
        assertThat(first.causes().get(0).detail()).contains("상품 파드 1개 강제 종료 11:06", "44초 뒤 복구");
        assertThat(first.sentence()).isEqualTo("11/17 11:07~11:09 상품 서비스에서 응답 실패 827건. 원인: 관리자 장애 주입(의도한 시험), 부하테스트 중(의도한 시험).");
        assertThat(first.logs()).extracting(l -> l.traceId()).containsExactly("abc123");

        Issue second = report.issues().get(1);
        assertThat(second.startMs()).isEqualTo(ms("11:12"));
        assertThat(second.serverErrors()).isEqualTo(2);
        assertThat(second.causes()).extracting(Cause::code).containsExactly("LOAD_TEST");
    }

    @Test
    @DisplayName("요청 제한 초과, 파드 사라짐(장애 주입 기록 없음), 탐색 요청 → TRAFFIC, ATTACK 이슈, 요약 문장")
    void trafficAttackAndSummary() {
        Report report = service.report(at("10:50"), at("11:30"));

        Issue traffic = report.issues().stream().filter(i -> "TRAFFIC".equals(i.kind())).findFirst().orElseThrow();
        assertThat(traffic.rateLimited()).isEqualTo(15);
        assertThat(traffic.intended()).isFalse();
        assertThat(traffic.causes()).extracting(Cause::code).contains("RATE_LIMIT", "POD_DOWN");
        assertThat(traffic.sentence()).contains("요청 제한 초과 15건");

        Issue attack = report.issues().stream().filter(i -> "ATTACK".equals(i.kind())).findFirst().orElseThrow();
        assertThat(attack.suspicious()).isEqualTo(3);
        assertThat(attack.intended()).isFalse();   // 외부 탐색은 우리 시험과 무관 — 늘 실제
        assertThat(attack.causes()).extracting(Cause::code).doesNotContain("CHAOS", "LOAD_TEST");
        assertThat(attack.causes().get(0).code()).isEqualTo("ATTACK");
        assertThat(attack.causes().get(0).detail()).contains("설정, 비밀 파일 탐색(/.env 등) 3건");

        assertThat(report.summary().issues()).isEqualTo(4);
        assertThat(report.summary().intendedIssues()).isEqualTo(2);
        assertThat(report.summary().serverErrors()).isEqualTo(829);
        assertThat(report.summary().headline())
                .isEqualTo("11/17 10:50 ~ 11/17 11:30 동안 이슈 4건(실제 장애 2건, 의도한 시험 2건). 응답 실패 총 829건, 공격 의심 요청 3건.");
        assertThat(report.suspicious()).hasSize(1);
    }

    @Test
    @DisplayName("이벤트 — 장애 주입, 복구, 파드 시작, 종료, 부하테스트, 부하 생성기 파드는 뺀다, 시간순")
    void events() {
        List<Event> events = service.report(at("10:50"), at("11:30")).events();

        assertThat(events).extracting(Event::type).containsExactly(
                "LOADTEST_START", "CHAOS_KILL", "POD_STOP", "POD_START", "CHAOS_RECOVER", "LOADTEST_END", "POD_STOP");
        assertThat(events).noneMatch(e -> e.detail().contains("loadtest"));
        assertThat(events.get(1).detail()).isEqualTo("관리자가 파드 1개 강제 종료(ALL)");
    }

    @Test
    @DisplayName("사라진 파드 이유 — 같은 시각 replicas 가 줄었으면(HPA 자동 축소) 비정상 종료가 아니고, 이벤트에 이유를 붙인다")
    void scaleDownIsNotPodDown() {
        when(prometheus.matrix(contains("kube_deployment_spec_replicas"), any(), any(), any())).thenReturn(Optional.of(List.of(
                new RangeSeries(Map.of("deployment", "order-service"), List.of(new Sample(ms("11:19"), 4), new Sample(ms("11:20"), 2))))));

        Report report = service.report(at("10:50"), at("11:30"));

        assertThat(report.issues()).flatExtracting(Issue::causes).extracting(Cause::code).doesNotContain("POD_DOWN");
        assertThat(report.events()).extracting(Event::detail)
                .contains("order-service-775744fdf6-gone 종료(자동 축소)", "product-service-757dd8467b-old 종료(장애 주입)");
    }

    @Test
    @DisplayName("점검용 요청 시간대의 탐색 요청은 공격 의심 건수와 로그에서 뺀다")
    void testWindow_excludesOwnProbes() {
        // 형식이 틀린 항목은 무시, 기간 밖 시간대는 조회하지 않음
        IncidentReportService withWindow = new IncidentReportService(prometheus, loki, chaos, Clock.fixed(NOW, KST),
                List.of("not-a-window", "2026-11-17T11:24:00+09:00/2026-11-17T11:25:00+09:00", "2026-11-16T01:00:00+09:00/2026-11-16T01:01:00+09:00"));
        when(loki.lines(contains("의심 요청"), any(), any(), anyInt())).thenReturn(List.of(
                new LogLine(ms("11:24") + 5_000, "gateway-service", "WARN", "의심 요청 type=secret-file path=/.env", null),
                new LogLine(ms("11:24") + 6_000, "gateway-service", "WARN", "유형 표시 없는 로그", null),
                new LogLine(ms("11:26"), "gateway-service", "WARN", "의심 요청 type=cms-probe path=/wp-admin", null)));
        when(loki.lines(contains("level=~"), any(), any(), anyInt())).thenReturn(List.of(
                new LogLine(ms("11:07"), "gateway-service", "WARN", "업무 서비스 연결 실패 route=product-service", "abc123"),
                new LogLine(ms("11:24") + 5_000, "gateway-service", "WARN", "의심 요청 type=secret-file path=/.env", null)));

        Report report = withWindow.report(at("10:50"), at("11:30"));

        Issue attack = report.issues().stream().filter(i -> "ATTACK".equals(i.kind())).findFirst().orElseThrow();
        assertThat(attack.suspicious()).isEqualTo(2);   // 3건 중 점검용 1건 제외
        assertThat(attack.logs()).extracting(l -> l.title())   // 근거 로그에서도 점검용 요청은 뺀다(가짜 Loki 는 시간과 상관없이 같은 목록)
                .doesNotContain("의심 요청 type=secret-file path=/.env");
        assertThat(report.issues().get(0).logs()).extracting(l -> l.traceId()).containsExactly("abc123");
        assertThat(report.suspicious()).extracting(l -> l.title())   // 점검용 시간대 안의 탐색 로그는 모두 빠지고 밖의 것만
                .containsExactly("의심 요청 type=cms-probe path=/wp-admin");
        assertThat(report.summary().suspicious()).isEqualTo(2);
    }

    @Test
    @DisplayName("이상이 없으면 '이상 없음' 한 문장")
    void quiet() {
        when(prometheus.matrix(contains("httpStatusCode=~\"5..\""), any(), any(), any())).thenReturn(Optional.empty());
        when(prometheus.matrix(contains("sum by (httpStatusCode)"), any(), any(), any()))
                .thenReturn(series(Map.of("httpStatusCode", "200"), new Sample(ms("08:01"), 7)));
        when(prometheus.matrix(contains("gateway_suspicious_requests_total"), any(), any(), any())).thenReturn(Optional.empty());

        Report report = service.report(at("08:00"), at("09:00"));

        assertThat(report.issues()).isEmpty();
        assertThat(report.summary().headline()).isEqualTo("11/17 08:00 ~ 11/17 09:00 동안 이상 없음. 요청 7건이 모두 정상 처리됐습니다.");
    }

    @Test
    @DisplayName("기간 — 시작이 끝보다 늦거나 7일 넘게, 7일 전보다 이르면 400")
    void invalidRange() {
        assertThatThrownBy(() -> service.report(at("11:00"), at("10:00"))).isInstanceOf(SystemException.class)
                .extracting("errorCode").isEqualTo(SystemErrorCode.SYSTEM_REPORT_INVALID_RANGE);
        assertThatThrownBy(() -> service.report(NOW.minus(Duration.ofDays(8)), NOW)).isInstanceOf(SystemException.class);
        assertThatThrownBy(() -> service.report(NOW.plusSeconds(60), NOW.plusSeconds(120))).isInstanceOf(SystemException.class);
    }

    @Test
    @DisplayName("원인 합치기 — 같은 code 는 한 줄, 근거는 이어 붙이고 4개 넘으면 '외 n건', 처음 순서 유지")
    void mergeCauses() {
        List<Cause> merged = IncidentReportService.merge(List.of(
                new Cause("CHAOS", "관리자 장애 주입(의도한 시험)", "a"), new Cause("LOAD_TEST", "부하테스트 중(의도한 시험)", "x"),
                new Cause("CHAOS", "관리자 장애 주입(의도한 시험)", "b"), new Cause("CHAOS", "관리자 장애 주입(의도한 시험)", "c"),
                new Cause("CHAOS", "관리자 장애 주입(의도한 시험)", "d"), new Cause("CHAOS", "관리자 장애 주입(의도한 시험)", "e"),
                new Cause("CHAOS", "관리자 장애 주입(의도한 시험)", "a")));

        assertThat(merged).extracting(Cause::code).containsExactly("CHAOS", "LOAD_TEST");
        assertThat(merged.get(0).detail()).isEqualTo("a, b, c, d 외 1건");
    }

    @Test
    @DisplayName("구간 크기, 파드 이름 → 서비스")
    void helpers() {
        assertThat(IncidentReportService.bucketFor(Duration.ofHours(1))).isEqualTo(Duration.ofMinutes(1));
        assertThat(IncidentReportService.bucketFor(Duration.ofHours(15))).isEqualTo(Duration.ofMinutes(5));
        assertThat(IncidentReportService.bucketFor(Duration.ofDays(2))).isEqualTo(Duration.ofMinutes(15));
        assertThat(IncidentReportService.bucketFor(Duration.ofDays(7))).isEqualTo(Duration.ofHours(1));
        assertThat(IncidentReportService.serviceOf("product-service-757dd8467b-ms5nt")).isEqualTo("product-service");
        assertThat(IncidentReportService.serviceOf("mysql-0")).isEqualTo("mysql");
        assertThat(IncidentReportService.serviceOf("loadtest-lt-1-abc")).isNull();
        assertThat(IncidentReportService.label("keycloak")).isEqualTo("로그인(Keycloak)");
    }
}
