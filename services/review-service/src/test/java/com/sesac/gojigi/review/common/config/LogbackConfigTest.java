package com.sesac.gojigi.review.common.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * logback-spring.xml 검증 — 9개 서비스 같은 테스트 (관측성 담당 박지연). XML 을 바꾸면 함께 고칠 것.
 * Spring 컨텍스트 없이 Boot 의 LogbackLoggingSystem 으로 실제 파일을 올리고(springProfile · springProperty 포함),
 * 가짜 Loki 가 받은 push 본문을 확인한다.
 */
class LogbackConfigTest {

    /** gotgan-config deploy/observability/grafana.yaml derivedFields matcherRegex 와 같은 값 — 바꾸면 함께 */
    private static final Pattern GRAFANA_TRACE_ID = Pattern.compile("\"traceId\"\\s*:\\s*\"(\\w+)\"");
    private static final String APP = "review-service";
    private static final String TRACE_ID = "6f3a9c1d2e4b5a70";
    private static final String SPAN_ID = "a1b2c3d4e5f60718";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private final LogbackLoggingSystem loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());
    private final Logger log = LoggerFactory.getLogger("com.sesac.gojigi.LogbackConfigTest");
    private HttpServer fakeLoki;

    @BeforeEach
    void startFakeLoki() throws IOException {
        fakeLoki = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLoki.createContext("/loki/api/v1/push", exchange -> {
            pushes.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        fakeLoki.start();
        System.setProperty("LOKI_URL", "http://127.0.0.1:" + fakeLoki.getAddress().getPort() + "/loki/api/v1/push");
    }

    @AfterEach
    void restoreLogging() {
        MDC.clear();
        initLogging();                  // 같은 JVM 의 다른 테스트를 위해 기본 프로필(콘솔만)로 되돌린다
        System.clearProperty("LOKI_URL");
        fakeLoki.stop(0);
    }

    @Test
    @DisplayName("k8s 프로필: MDC 의 traceId 가 \"traceId\" 키 그대로 Loki 본문에 들어가 Grafana 정규식에 걸린다 (mdc_ 접두어 아님)")
    void k8sProfile_traceIdInMdc_sendsTraceIdMatchingGrafanaRegex() {
        initLogging("k8s");
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        log.info("주문 생성");

        String line = singleLine(flushAndReceive());

        assertThat(GRAFANA_TRACE_ID.matcher(line).results().map(m -> m.group(1))).containsExactly(TRACE_ID);
        JsonNode body = json.readTree(line);
        assertThat(body.path("spanId").asString()).isEqualTo(SPAN_ID);
        assertThat(body.path("message").asString()).isEqualTo("주문 생성");
        assertThat(body.has("mdc_traceId")).isFalse();
    }

    @Test
    @DisplayName("k8s 프로필: Loki 라벨은 app · namespace · level 3개뿐 (traceId 같은 값은 라벨 금지 — 카디널리티)")
    void k8sProfile_anyLog_sendsOnlyThreeLabels() {
        initLogging("k8s");
        MDC.put("traceId", TRACE_ID);
        log.warn("재고 부족");

        List<JsonNode> streams = flushAndReceive();

        assertThat(streams).hasSize(1);
        JsonNode labels = streams.getFirst().path("stream");
        assertThat(labels.propertyNames()).containsExactlyInAnyOrder("app", "namespace", "level");
        assertThat(labels.path("app").asString()).isEqualTo(APP);
        assertThat(labels.path("namespace").asString()).isEqualTo("sesac-gojigi");
        assertThat(labels.path("level").asString()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("k8s 프로필: 예외 로그도 올바른 JSON 한 줄이고 스택 트레이스는 stack_trace 필드에 들어간다")
    void k8sProfile_exception_sendsValidJsonWithStackTrace() {
        initLogging("k8s");
        log.error("결제 실패", new IllegalStateException("재고 부족"));

        JsonNode body = json.readTree(singleLine(flushAndReceive()));

        assertThat(body.path("level").asString()).isEqualTo("ERROR");
        assertThat(body.path("stack_trace").asString()).startsWith("java.lang.IllegalStateException: 재고 부족");
    }

    @Test
    @DisplayName("기본 프로필(로컬): Loki 로 아무것도 보내지 않는다")
    void defaultProfile_anyLog_doesNotSendToLoki() {
        initLogging();
        MDC.put("traceId", TRACE_ID);
        log.info("로컬 로그");

        assertThat(flushAndReceive()).isEmpty();
    }

    /** 앱이 뜰 때처럼 logback-spring.xml 을 올린다. 이미 올라가 있으면 Boot 가 건너뛰므로 먼저 내린다 */
    private void initLogging(String... profiles) {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", APP);
        environment.setActiveProfiles(profiles);
        loggingSystem.cleanUp();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
    }

    /** 앱 종료처럼 로거를 멈춰 남은 배치를 보내게 한 뒤, 가짜 Loki 가 받은 스트림 전부 */
    private List<JsonNode> flushAndReceive() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        List<JsonNode> streams = new ArrayList<>();
        pushes.forEach(push -> json.readTree(push).path("streams").forEach(streams::add));
        return streams;
    }

    private static String singleLine(List<JsonNode> streams) {
        assertThat(streams).hasSize(1);
        JsonNode values = streams.getFirst().path("values");
        assertThat(values.size()).isEqualTo(1);
        return values.get(0).get(1).asString();    // [타임스탬프, 로그 한 줄]
    }
}
