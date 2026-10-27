package com.sesac.gojigi.admin.notification.realtime;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 이 파드에 연결된 SSE 화면들 — 받는 쪽(USER#… · STORE#… · ADMIN)마다 열린 연결. 한 화면이 여러 받는 쪽을 듣는다(판매자 = 회원 + 스토어).
 * 끊긴 연결은 보내다 실패하거나 완료 · 시간 초과 때 지운다. heartbeat 주석으로 NLB(idle 350초) · 프록시가 연결을 끊지 않게 한다.
 */
@Slf4j
@Component
public class NotificationStreams {

    private final Map<String, Set<SseEmitter>> byRecipient = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("sse-heartbeat").factory());

    public NotificationStreams(@Value("${app.notification.heartbeat:25s}") Duration interval) {
        heartbeat.scheduleAtFixedRate(this::beat, interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
    }

    /** 새 연결 — 시간 제한 없음(끊기면 브라우저가 다시 연결), 연결 직후 ready 이벤트 */
    public SseEmitter open(List<String> recipients) {
        SseEmitter emitter = new SseEmitter(0L);
        recipients.forEach(r -> byRecipient.computeIfAbsent(r, k -> ConcurrentHashMap.newKeySet()).add(emitter));
        Runnable remove = () -> recipients.forEach(r -> byRecipient.computeIfPresent(r, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        }));
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(emitter, SseEmitter.event().name("ready").data("ok"));
        return emitter;
    }

    /** 이 파드에서 그 받는 쪽을 듣는 화면 모두에 알림 하나 (json = NotificationResponse) */
    public void deliver(String recipient, String json) {
        byRecipient.getOrDefault(recipient, Set.of())
                .forEach(e -> send(e, SseEmitter.event().name("notification").data(json, MediaType.APPLICATION_JSON)));
    }

    /** 지금 열린 연결 수 (같은 화면이 여러 받는 쪽을 들으면 한 번만) */
    public long connections() {
        return byRecipient.values().stream().flatMap(Set::stream).distinct().count();
    }

    private void beat() {
        byRecipient.values().stream().flatMap(Set::stream).distinct().forEach(e -> send(e, SseEmitter.event().comment("hb")));
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {   // 이미 닫힌 연결 — onError/onCompletion 이 목록에서 지운다
            emitter.completeWithError(e);
        }
    }

    @PreDestroy
    void close() {
        heartbeat.shutdownNow();
        byRecipient.values().stream().flatMap(Set::stream).distinct().forEach(SseEmitter::complete);   // 종료 · 배포 때 화면이 바로 다시 연결하게
        log.info("SSE 연결 종료 - {}개", connections());
    }
}
