package com.sesac.gojigi.chat.realtime;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 메시지 전송 속도 — 연결마다 1초에 {@value #LIMIT}건 (chat.md 6절 CHAT_TOO_FAST). 연결은 한 파드에 붙어 있어 Redis 없이 메모리로 센다.
 * 상태는 연결이 끊길 때 지운다(forget) — 연결 수만큼만 남는다.
 * 입력 중 신호는 따로 연결마다 {@value #TYPING_INTERVAL_MILLIS}ms 에 1번만 전달(화면은 3초마다 보냄) — 넘치는 것은 조용히 버린다
 */
@Component
public class ChatRateLimiter {

    static final int LIMIT = 5;
    private static final long WINDOW_MILLIS = 1_000;
    static final long TYPING_INTERVAL_MILLIS = 1_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> typingAt = new ConcurrentHashMap<>();
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

    boolean tryTyping(String sessionId) {
        long now = clock.millis();
        // 들어오는 채널 스레드가 여러 개라 확인과 기록을 한 번에 (같은 연결의 신호가 동시에 와도 하나만 통과)
        boolean[] passed = {false};
        typingAt.compute(sessionId, (id, last) -> {
            if (last != null && now - last < TYPING_INTERVAL_MILLIS) {
                return last;
            }
            passed[0] = true;
            return now;
        });
        return passed[0];
    }

    void forget(String sessionId) {
        windows.remove(sessionId);
        typingAt.remove(sessionId);
    }

    int size() {
        return windows.size() + typingAt.size();
    }

    private record Window(long start, int count) {
    }
}
