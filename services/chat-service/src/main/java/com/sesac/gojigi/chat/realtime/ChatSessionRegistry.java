package com.sesac.gojigi.chat.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 이 파드의 WebSocket 연결 목록 — 좀비 연결 정리 (chat.md 6-1절). 끊긴 연결(하트비트 없음)은 브로커가 닫고, 여기서는 그 밖의 경우를 닫는다.
 * <ul>
 *   <li>CONNECT 가 거절된 연결 — ERROR 프레임 뒤 1초 안에 (rejected)</li>
 *   <li>CONNECT 없이 {@value #CONNECT_GRACE_SECONDS}초 넘게 열린 연결</li>
 *   <li>토큰이 만료된 연결 — 탈퇴 · 정지된 회원이 열어 둔 연결도 토큰 수명(30분) 안에 끊긴다</li>
 *   <li>한 사용자의 연결이 상한을 넘으면 가장 오래된 것 — 탭을 많이 열거나 화면이 정리하지 못한 연결이 쌓이지 않게</li>
 * </ul>
 * 연결 수는 지표 chat_ws_sessions 로 Grafana 에서 본다
 */
@Slf4j
@Component
public class ChatSessionRegistry {

    static final long CONNECT_GRACE_SECONDS = 30;
    static final long REJECT_CLOSE_DELAY_MILLIS = 1_000;
    static final CloseStatus UNAUTHENTICATED = new CloseStatus(4401, "unauthenticated");
    static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4401, "token expired");
    static final CloseStatus TOO_MANY = new CloseStatus(4429, "too many connections");

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxPerUser;

    public ChatSessionRegistry(Clock clock, MeterRegistry meterRegistry,
            @Value("${app.chat.ws.max-sessions-per-user}") int maxPerUser) {
        this.clock = clock;
        this.maxPerUser = maxPerUser;
        Gauge.builder("chat.ws.sessions", sessions, Map::size).description("이 파드의 채팅 WebSocket 연결 수").register(meterRegistry);
    }

    void opened(WebSocketSession session) {
        sessions.put(session.getId(), new Entry(session, clock.instant(), null));
    }

    void closed(String sessionId) {
        sessions.remove(sessionId);
    }

    /** CONNECT 검증 통과 — 같은 사용자 연결이 상한을 넘으면 오래된 것부터 닫는다 */
    void authenticated(String sessionId, ChatPrincipal principal) {
        Entry entry = sessions.computeIfPresent(sessionId, (id, e) -> new Entry(e.session(), e.openedAt(), principal));
        if (entry == null) {
            return;
        }
        List<Entry> mine = sessions.values().stream()
                .filter(e -> e.principal() != null && e.principal().userId().equals(principal.userId()))
                .sorted(Comparator.comparing(Entry::openedAt))
                .toList();
        mine.stream().limit(Math.max(0, mine.size() - maxPerUser)).forEach(e -> close(e, TOO_MANY));
    }

    /** CONNECT 거절 — ERROR 프레임이 나갈 시간(1초)을 두고 서버가 닫는다. 화면이 닫지 않아도 연결이 남지 않게 */
    void rejected(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry != null) {
            CompletableFuture.delayedExecutor(REJECT_CLOSE_DELAY_MILLIS, TimeUnit.MILLISECONDS).execute(() -> close(entry, UNAUTHENTICATED));
        }
    }

    int size() {
        return sessions.size();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void sweep() {
        Instant now = clock.instant();
        Instant connectDeadline = now.minus(Duration.ofSeconds(CONNECT_GRACE_SECONDS));
        for (Entry entry : sessions.values()) {
            if (entry.principal() == null && entry.openedAt().isBefore(connectDeadline)) {
                close(entry, UNAUTHENTICATED);
            } else if (entry.principal() != null && entry.principal().expired(now)) {
                close(entry, TOKEN_EXPIRED);
            }
        }
    }

    /** 닫으면 afterConnectionClosed → closed() · SessionDisconnectEvent → 접속 중 표시 · 속도 제한 정리 */
    private void close(Entry entry, CloseStatus status) {
        sessions.remove(entry.session().getId());
        try {
            entry.session().close(status);
        } catch (IOException e) {
            log.debug("채팅 연결 닫기 실패(이미 닫힘) session={}, cause={}", entry.session().getId(), e.toString());
        }
    }

    private record Entry(WebSocketSession session, Instant openedAt, ChatPrincipal principal) {
    }
}
