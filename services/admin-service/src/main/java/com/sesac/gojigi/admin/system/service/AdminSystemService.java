package com.sesac.gojigi.admin.system.service;

import com.sesac.gojigi.admin.system.client.KubernetesClient;
import com.sesac.gojigi.admin.system.client.KubernetesClient.Pod;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.dto.request.KillRequest;
import com.sesac.gojigi.admin.system.dto.request.ScenarioRequest;
import com.sesac.gojigi.admin.system.dto.response.ServiceState;
import com.sesac.gojigi.admin.system.dto.response.SystemResponses;
import com.sesac.gojigi.admin.system.entity.ChaosEvent;
import com.sesac.gojigi.admin.system.entity.KillScope;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import com.sesac.gojigi.admin.system.repository.ChaosEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 장애 시연 — 파드 상태로 서비스 · 기능 상태를 계산하고, 허용 목록 서비스의 파드를 지운다.
 * 복구 시각은 별도 감시 없이 상태 조회(화면 2초 폴링)가 "내린 뒤 원하는 수만큼 Ready" 를 처음 본 때 기록한다
 */
@Slf4j
@Service
public class AdminSystemService {

    static final Duration KILL_COOLDOWN = Duration.ofMinutes(1);

    /** 시나리오 대상 — 레플리카 2 이상(구매 경로 이중화). 1개씩 지워도 남은 파드가 받으므로 끊김이 없어야 한다 */
    static final int SCENARIO_MIN_REPLICAS = 2;
    static final String SCENARIO_DEFAULT_REASON = "시나리오 — 레플리카 2 서비스 1개씩 내리기";

    /** gateway 라우트 지표 — 1분 rate. 서비스를 내리면 gateway 가 502 로 센다(11/6 장애 영향표) */
    static final String REQUESTS_QUERY = "sum by (routeId)(rate(spring_cloud_gateway_requests_seconds_count[1m]))";
    static final String ERRORS_QUERY = "sum by (routeId)(rate(spring_cloud_gateway_requests_seconds_count{httpStatusCode=~\"5..\"}[1m]))";

    private final KubernetesClient kubernetes;
    private final PrometheusClient prometheus;
    private final ChaosEventRepository chaosEventRepository;
    private final Clock clock;
    private final boolean chaosEnabled;

    public AdminSystemService(KubernetesClient kubernetes, PrometheusClient prometheus, ChaosEventRepository chaosEventRepository,
            Clock clock, @Value("${app.chaos.enabled:false}") boolean chaosEnabled) {
        this.kubernetes = kubernetes;
        this.prometheus = prometheus;
        this.chaosEventRepository = chaosEventRepository;
        this.clock = clock;
        this.chaosEnabled = chaosEnabled;
    }

    /** 서비스별 요청량 · 오류율 — Prometheus 가 안 되면 200 에 값 null (status 와 달리 503 아님) */
    public SystemResponses.Metrics metrics() {
        Optional<Map<String, Double>> requests = prometheus.byRoute(REQUESTS_QUERY);
        Optional<Map<String, Double>> errors = requests.isPresent() ? prometheus.byRoute(ERRORS_QUERY) : Optional.empty();
        return new SystemResponses.Metrics(requests.isPresent() && errors.isPresent()
                ? SystemCatalog.serviceMetrics(requests.get(), errors.get())
                : SystemCatalog.unknownMetrics());
    }

    /** 서비스 상태 + 사용자 기능 — 쿠버네티스 조회 실패면 503 (화면은 마지막 값 유지 · "연결 끊김") */
    public SystemResponses.Status status() {
        Instant now = clock.instant();
        Map<String, Integer> desired = kubernetes.desiredReplicas();
        Map<String, List<Pod>> podsByApp = kubernetes.pods().stream().collect(Collectors.groupingBy(Pod::app));
        Map<String, ChaosEvent> lastEvents = chaosEventRepository.findLatestPerService().stream()
                .collect(Collectors.toMap(ChaosEvent::getServiceName, Function.identity()));

        List<SystemResponses.ServiceStatus> services = SystemCatalog.SERVICES.stream()
                .map(t -> serviceStatus(t, desired.getOrDefault(t.name(), 0), podsByApp.getOrDefault(t.name(), List.of()),
                        lastEvents.get(t.name()), now))
                .toList();
        Map<String, ServiceState> states = services.stream()
                .collect(Collectors.toMap(SystemResponses.ServiceStatus::name, SystemResponses.ServiceStatus::state, (a, b) -> a, LinkedHashMap::new));
        return new SystemResponses.Status(chaosEnabled, OffsetDateTime.ofInstant(now, clock.getZone()), services,
                SystemCatalog.featureStatuses(states));
    }

    /**
     * 내리기 — 꺼짐 403 · 허용 목록 밖 400 · 서비스당 1분 · 복구 중 409. ONE = Ready 파드 1개(이름순 첫 번째), ALL = 지워지는 중이 아닌 파드 전부.
     * 관리자 파드가 1개라 동시 요청은 synchronized 로 막는다
     */
    public synchronized SystemResponses.Killed kill(String name, KillRequest request, Long actorId) {
        requireEnabled();
        SystemCatalog.Target target = SystemCatalog.SERVICES.stream().filter(t -> t.killable() && t.name().equals(name)).findFirst()
                .orElseThrow(() -> new SystemException(SystemErrorCode.SYSTEM_SERVICE_NOT_KILLABLE));
        Instant now = clock.instant();
        ChaosEvent last = chaosEventRepository.findFirstByServiceNameOrderByIdDesc(name).orElse(null);
        requireOffCooldown(last, now);

        List<Pod> pods = kubernetes.pods().stream().filter(p -> name.equals(p.app())).toList();
        SystemResponses.ServiceStatus current = serviceStatus(target, kubernetes.desiredReplicas().getOrDefault(name, 0), pods, last, now);
        if (current.state() == ServiceState.RECOVERING) {
            throw new SystemException(SystemErrorCode.SYSTEM_KILL_COOLDOWN);
        }
        List<String> victims = victims(pods, request.scope());
        if (victims.isEmpty()) {
            throw new SystemException(SystemErrorCode.SYSTEM_NO_READY_POD);
        }

        ChaosEvent saved = deleteAndRecord(name, request.scope(), request.reason(), victims, actorId, now);
        log.info("장애 시연 내리기 - service={}, scope={}, pods={}, actorId={}", name, request.scope(), saved.deletedPodList(), actorId);
        return new SystemResponses.Killed(saved.getId(), saved.deletedPodList());
    }

    /**
     * 시나리오 — 레플리카 2 이상인 내릴 수 있는 서비스마다 Ready 파드 1개씩(ONE) 한 번에 지운다. 서비스마다 1행씩 기록(타임라인 · 복구 시간 그대로).
     * 대상이 하나라도 1분 제한 · 정상(UP) 아님이면 아무것도 지우지 않는다 — 남은 파드가 없으면 「1개씩 지워도 괜찮다」가 성립하지 않으므로
     */
    public synchronized SystemResponses.ScenarioKilled killOneEach(ScenarioRequest request, Long actorId) {
        requireEnabled();
        Instant now = clock.instant();
        Map<String, Integer> desired = kubernetes.desiredReplicas();
        Map<String, List<Pod>> podsByApp = kubernetes.pods().stream().collect(Collectors.groupingBy(Pod::app));
        List<SystemCatalog.Target> targets = SystemCatalog.SERVICES.stream()
                .filter(t -> t.killable() && desired.getOrDefault(t.name(), 0) >= SCENARIO_MIN_REPLICAS).toList();
        if (targets.isEmpty()) {
            throw new SystemException(SystemErrorCode.SYSTEM_NO_READY_POD);
        }

        Map<String, List<String>> plan = new LinkedHashMap<>();
        for (SystemCatalog.Target target : targets) {
            List<Pod> pods = podsByApp.getOrDefault(target.name(), List.of());
            ChaosEvent last = chaosEventRepository.findFirstByServiceNameOrderByIdDesc(target.name()).orElse(null);
            requireOffCooldown(last, now);
            if (serviceStatus(target, desired.get(target.name()), pods, last, now).state() != ServiceState.UP) {
                throw new SystemException(SystemErrorCode.SYSTEM_SCENARIO_NOT_READY);
            }
            plan.put(target.name(), victims(pods, KillScope.ONE));   // UP 이고 desired ≥ 2 라 Ready 파드가 있음
        }

        String reason = request == null || request.reason() == null || request.reason().isBlank() ? SCENARIO_DEFAULT_REASON : request.reason();
        List<SystemResponses.ServiceKilled> killed = new ArrayList<>();
        plan.forEach((name, victims) -> {
            ChaosEvent saved = deleteAndRecord(name, KillScope.ONE, reason, victims, actorId, now);
            killed.add(new SystemResponses.ServiceKilled(name, saved.getId(), saved.deletedPodList()));
        });
        log.info("장애 시연 시나리오 1개씩 내리기 - services={}, actorId={}", plan.keySet(), actorId);
        return new SystemResponses.ScenarioKilled(killed);
    }

    /** 타임라인 — 최신순 20건 */
    public List<SystemResponses.EventItem> events() {
        return chaosEventRepository.findTop20ByOrderByIdDesc().stream().map(SystemResponses.EventItem::of).toList();
    }

    private void requireEnabled() {
        if (!chaosEnabled) {
            throw new SystemException(SystemErrorCode.SYSTEM_CHAOS_DISABLED);
        }
    }

    private void requireOffCooldown(ChaosEvent last, Instant now) {
        if (last != null && last.getKilledAt().isAfter(local(now).minus(KILL_COOLDOWN))) {
            throw new SystemException(SystemErrorCode.SYSTEM_KILL_COOLDOWN);
        }
    }

    /** 파드를 지우고 1행 기록 — 일부만 지우고 실패해도 지운 만큼은 기록하고 예외를 그대로 던진다 */
    private ChaosEvent deleteAndRecord(String name, KillScope scope, String reason, List<String> victims, Long actorId, Instant now) {
        List<String> deleted = new ArrayList<>();
        ChaosEvent saved = null;
        try {
            for (String pod : victims) {
                kubernetes.deletePod(pod);
                deleted.add(pod);
            }
        } finally {
            if (!deleted.isEmpty()) {
                String stripped = reason == null || reason.isBlank() ? null : reason.strip();
                saved = chaosEventRepository.save(ChaosEvent.of(name, scope, stripped, deleted, actorId, local(now)));
            }
        }
        return saved;
    }

    private static List<String> victims(List<Pod> pods, KillScope scope) {
        List<Pod> alive = pods.stream().filter(p -> !p.terminating()).sorted(Comparator.comparing(Pod::name)).toList();
        if (scope == KillScope.ALL) {
            return alive.stream().map(Pod::name).toList();
        }
        return alive.stream().filter(Pod::ready).limit(1).map(Pod::name).toList();
    }

    /** 한 서비스 상태 — 마지막 내리기가 복구 전인데 원하는 수만큼 Ready 면 지금을 복구 시각으로 남긴다 */
    private SystemResponses.ServiceStatus serviceStatus(SystemCatalog.Target target, int desired, List<Pod> pods, ChaosEvent last,
            Instant now) {
        int ready = (int) pods.stream().filter(p -> p.ready() && !p.terminating()).count();
        boolean recovering = last != null && !last.recovered();
        if (recovering && desired > 0 && ready >= desired) {
            last.markRecovered(local(now));
            chaosEventRepository.save(last);
            recovering = false;
        }
        List<SystemResponses.PodItem> items = pods.stream().sorted(Comparator.comparing(Pod::name))
                .map(p -> new SystemResponses.PodItem(p.name(), p.node(), p.phase(), p.ready(), p.restarts(),
                        p.createdAt() == null ? 0 : Math.max(0, Duration.between(p.createdAt(), now).toSeconds()), p.terminating()))
                .toList();
        return new SystemResponses.ServiceStatus(target.name(), target.killable(), desired, ready,
                SystemCatalog.serviceState(desired, ready, recovering), items);
    }

    private LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, clock.getZone());
    }
}
