package com.sesac.gojigi.chat.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * 연결이 끝나면 그 연결의 상태를 한 번에 지운다 — DISCONNECT 프레임 · 탭 닫기 · 네트워크 끊김(하트비트 없음) · 서버가 닫은 경우 모두 이 이벤트 하나로 온다
 */
@Component
@RequiredArgsConstructor
public class ChatSessionEvents {

    private final ChatPresence presence;
    private final ChatRateLimiter rateLimiter;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presence.leaveAll(event.getSessionId());
        rateLimiter.forget(event.getSessionId());
    }
}
