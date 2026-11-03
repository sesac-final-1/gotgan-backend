package com.sesac.gojigi.common.logging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.LoggerContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * logback-base.xml 검증 — 10개 서비스가 include 하는 공통 설정의 상세 검증은 여기 한 벌 (관측성 담당 박지연).
 * 서비스와 같은 모양의 logback-module-test.xml(springProperty + include)을 Boot 의 LogbackLoggingSystem 으로 올려
 * include 안의 springProfile 이 동작하는지, 가짜 Loki 가 받은 push 본문이 대시보드 · Grafana 링크 계약과 맞는지 본다.
 * loki4j 2.1.0 은 stop() 중 종료 경합으로 마지막 배치를 버릴 수 있다 → 배치 간격을 100ms 로 줄여 평소 전송으로 받은 뒤에 stop() 한다.
 * 같은 JVM 의 다른 로거 줄이 섞여도 깨지지 않게 이 테스트 로거(logger_name)의 줄만 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogbackBaseTest {

    /** gotgan-config deploy/observability/grafana.yaml derivedFields matcherRegex 와 같은 값 — 바꾸면 함께 */
    private static final Pattern GRAFANA_TRACE_ID = Pattern.compile("\"traceId\"\\s*:\\s*\"(\\w+)\"");
    private static final String CONFIG = "classpath:logback-module-test.xml";
    private static final String APP = "sample-service";
    private static final String LOGGER = "com.sesac.gojigi.common.logging.LogbackBaseTest";
    private static final String TRACE_ID = "6f3a9c1d2e4b5a70";
    private static final String SPAN_ID = "a1b2c3d4e5f60718";
    private static final String USER_ID = "4";
    private static final String LOKI_BATCH_TIMEOUT_MS = "100";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private final LogbackLoggingSystem loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());
    private final Logger log = LoggerFactory.getLogger(LOGGER);
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
        System.setProperty("LOKI_BATCH_TIMEOUT_MS", LOKI_BATCH_TIMEOUT_MS);
    }

    @AfterEach
    void restoreLogging() {
        MDC.clear();
        initLogging();                  // 같은 JVM 의 다른 테스트를 위해 기본 프로필(콘솔만)로 되돌린다
        System.clearProperty("LOKI_URL");
        System.clearProperty("LOKI_BATCH_TIMEOUT_MS");
        fakeLoki.stop(0);
    }

    @Test
    @DisplayName("k8s 프로필: MDC 의 traceId 가 \"traceId\" 키 그대로 Loki 본문에 들어가 Grafana 정규식에 걸린다 (mdc_ 접두어 아님)")
    void k8sProfile_traceIdInMdc_sendsTraceIdMatchingGrafanaRegex() {
        // given
        initLogging("k8s");
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        MDC.put("userId", USER_ID);

        // when
        log.info("주문 생성");

        // then
        String line = singleLine(receive());
        assertThat(GRAFANA_TRACE_ID.matcher(line).results().map(m -> m.group(1))).containsExactly(TRACE_ID);
        JsonNode body = json.readTree(line);
        assertThat(body.path("spanId").asString()).isEqualTo(SPAN_ID);
        assertThat(body.path("userId").asString()).isEqualTo(USER_ID);
        assertThat(body.path("message").asString()).isEqualTo("주문 생성");
        assertThat(body.has("mdc_traceId")).isFalse();
    }

    @Test
    @DisplayName("k8s 프로필: Loki 라벨은 app · namespace · level 3개뿐, app 은 서비스의 spring.application.name (traceId 같은 값은 라벨 금지 — 카디널리티)")
    void k8sProfile_anyLog_sendsOnlyThreeLabels() {
        // given
        initLogging("k8s");
        MDC.put("traceId", TRACE_ID);

        // when
        log.warn("재고 부족");

        // then
        List<Line> lines = receive();
        assertThat(lines).hasSize(1);
        JsonNode labels = lines.getFirst().labels();
        assertThat(labels.propertyNames()).containsExactlyInAnyOrder("app", "namespace", "level");
        assertThat(labels.path("app").asString()).isEqualTo(APP);
        assertThat(labels.path("namespace").asString()).isEqualTo("sesac-gojigi");
        assertThat(labels.path("level").asString()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("k8s 프로필: 예외 로그도 올바른 JSON 한 줄이고 스택 트레이스는 stack_trace 필드에 들어간다")
    void k8sProfile_exception_sendsValidJsonWithStackTrace() {
        // given
        initLogging("k8s");

        // when
        log.error("결제 실패", new IllegalStateException("재고 부족"));

        // then
        JsonNode body = json.readTree(singleLine(receive()));
        assertThat(body.path("level").asString()).isEqualTo("ERROR");
        assertThat(body.path("stack_trace").asString()).startsWith("java.lang.IllegalStateException: 재고 부족");
    }

    @Test
    @DisplayName("기본 프로필(로컬): Loki 로 아무것도 보내지 않는다")
    void defaultProfile_anyLog_doesNotSendToLoki() {
        // given
        initLogging();
        MDC.put("traceId", TRACE_ID);

        // when
        log.info("로컬 로그");
        stopLogging();

        // then
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(1)).until(pushes::isEmpty);
    }

    @Test
    @DisplayName("기본 프로필(로컬): 콘솔 한 줄에 [app] [traceId,spanId,userId] 가 찍힌다 — userId 는 common-web UserIdMdcFilter 가 넣는다")
    void defaultProfile_userIdInMdc_printsTraceSpanUserOnConsole(CapturedOutput output) {
        // given
        initLogging();
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        MDC.put("userId", USER_ID);

        // when
        log.info("콘솔 로그");

        // then
        assertThat(output.getOut()).contains("[" + APP + "] [" + TRACE_ID + "," + SPAN_ID + "," + USER_ID + "] ")
                .contains("콘솔 로그");
    }

    /** 앱이 뜰 때처럼 설정 파일을 올린다. 이미 올라가 있으면 Boot 가 건너뛰므로 먼저 내린다 */
    private void initLogging(String... profiles) {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", APP);
        environment.setActiveProfiles(profiles);
        loggingSystem.cleanUp();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), CONFIG, null);
    }

    /** 받은 로그 한 줄 — 그 줄이 속한 스트림의 Loki 라벨 + JSON 본문 */
    private record Line(JsonNode labels, String body) {
    }

    /** 평소 전송(100ms 간격)으로 이 테스트 로거의 줄을 받을 때까지 기다린 뒤 로거를 멈춘다 — stop() 을 먼저 하면 배치가 버려질 수 있다 */
    private List<Line> receive() {
        await().atMost(Duration.ofSeconds(3)).until(() -> !linesOfThisLogger().isEmpty());
        stopLogging();
        return linesOfThisLogger();
    }

    /** 받은 push 전부에서 이 테스트 로거(logger_name)의 줄만 — 다른 로거 줄이 섞여도 같은 결과 */
    private List<Line> linesOfThisLogger() {
        List<Line> lines = new ArrayList<>();
        for (String push : pushes) {
            for (JsonNode stream : json.readTree(push).path("streams")) {
                for (JsonNode value : stream.path("values")) {
                    String body = value.get(1).asString();     // [타임스탬프, 로그 한 줄]
                    if (LOGGER.equals(json.readTree(body).path("logger_name").asString())) {
                        lines.add(new Line(stream.path("stream"), body));
                    }
                }
            }
        }
        return lines;
    }

    private static void stopLogging() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
    }

    private static String singleLine(List<Line> lines) {
        assertThat(lines).hasSize(1);
        return lines.getFirst().body();
    }
}
