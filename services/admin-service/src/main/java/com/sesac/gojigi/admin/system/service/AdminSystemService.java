package com.sesac.gojigi.admin.system.service;

import com.sesac.gojigi.admin.system.client.KubernetesClient;
import com.sesac.gojigi.admin.system.client.KubernetesClient.Pod;
import com.sesac.gojigi.admin.system.dto.request.KillRequest;
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

    private final KubernetesClient kubernetes;
    private final ChaosEventRepository chaosEventRepository;
    private final Clock clock;
    private final boolean chaosEnabled;

    public AdminSystemService(KubernetesClient kubernetes, ChaosEventRepository chaosEventRepository, Clock clock,
            @Value("${app.chaos.enabled:false}") boolean chaosEnabled) {
        this.kubernetes = kubernetes;
        this.chaosEventRepository = chaosEventRepository;
        this.clock = clock;
        this.chaosEnabled = chaosEnabled;
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
                SystemCatalog.features(states));
    }

    /**
     * 내리기 — 꺼짐 403 · 허용 목록 밖 400 · 서비스당 1분 · 복구 중 409. ONE = Ready 파드 1개(이름순 첫 번째), ALL = 지워지는 중이 아닌 파드 전부.
     * 관리자 파드가 1개라 동시 요청은 synchronized 로 막는다
     */
    public synchronized SystemResponses.Killed kill(String name, KillRequest request, Long actorId) {
        if (!chaosEnabled) {
            throw new SystemException(SystemErrorCode.SYSTEM_CHAOS_DISABLED);
        }
        SystemCatalog.Target target = SystemCatalog.SERVICES.stream().filter(t -> t.killable() && t.name().equals(name)).findFirst()
                .orElseThrow(() -> new SystemException(SystemErrorCode.SYSTEM_SERVICE_NOT_KILLABLE));
        Instant now = clock.instant();
        ChaosEvent last = chaosEventRepository.findFirstByServiceNameOrderByIdDesc(name).orElse(null);
        if (last != null && last.getKilledAt().isAfter(local(now).minus(KILL_COOLDOWN))) {
            throw new SystemException(SystemErrorCode.SYSTEM_KILL_COOLDOWN);
        }

        List<Pod> pods = kubernetes.pods().stream().filter(p -> name.equals(p.app())).toList();
        SystemResponses.ServiceStatus current = serviceStatus(target, kubernetes.desiredReplicas().getOrDefault(name, 0), pods, last, now);
        if (current.state() == ServiceState.RECOVERING) {
            throw new SystemException(SystemErrorCode.SYSTEM_KILL_COOLDOWN);
        }
        List<String> victims = victims(pods, request.scope());
        if (victims.isEmpty()) {
            throw new SystemException(SystemErrorCode.SYSTEM_NO_READY_POD);
        }

        List<String> deleted = new ArrayList<>();
        ChaosEvent saved = null;
        try {
            for (String pod : victims) {
                kubernetes.deletePod(pod);
                deleted.add(pod);
            }
        } finally {
            if (!deleted.isEmpty()) {   // 일부만 지우고 실패해도 지운 만큼은 기록
                saved = record(name, request, actorId, deleted, now);
            }
        }
        log.info("장애 시연 내리기 - service={}, scope={}, pods={}, actorId={}", name, request.scope(), deleted, actorId);
        return new SystemResponses.Killed(saved.getId(), deleted);
    }

    /** 타임라인 — 최신순 20건 */
    public List<SystemResponses.EventItem> events() {
        return chaosEventRepository.findTop20ByOrderByIdDesc().stream().map(SystemResponses.EventItem::of).toList();
    }

    private ChaosEvent record(String name, KillRequest request, Long actorId, List<String> deleted, Instant killedAt) {
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        return chaosEventRepository.save(ChaosEvent.of(name, request.scope(), reason, deleted, actorId, local(killedAt)));
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
