package com.sesac.gojigi.chat.realtime;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 메시지 전송 속도 — 연결마다 1초에 {@value #LIMIT}건 (chat.md 6절 CHAT_TOO_FAST). 연결은 한 파드에 붙어 있어 Redis 없이 메모리로 센다.
 * 상태는 연결이 끊길 때 지운다(forget) — 연결 수만큼만 남는다
 */
@Component
public class ChatRateLimiter {

    static final int LIMIT = 5;
    private static final long WINDOW_MILLIS = 1_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public ChatRateLimiter(Clock clock) {
        this.clock = clock;
    }

    boolean tryAcquire(String sessionId) {
        long now = clock.millis();
        Window window = windows.compute(sessionId, (id, w) ->
                w == null || now - w.start() >= WINDOW_MILLIS ? new Window(now, 1) : new Window(w.start(), w.count() + 1));
        return window.count() <= LIMIT;
    }

    void forget(String sessionId) {
        windows.remove(sessionId);
    }

    int size() {
        return windows.size();
    }

    private record Window(long start, int count) {
    }
}
