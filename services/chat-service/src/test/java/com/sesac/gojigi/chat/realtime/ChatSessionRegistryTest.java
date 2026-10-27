package com.sesac.gojigi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 연결 목록 정리 규칙 — 미인증 30초 · 토큰 만료 · 사용자당 상한 (시계를 바꿔 가며) */
class ChatSessionRegistryTest {

    private static final Instant T0 = Instant.parse("2026-10-27T05:00:00Z");

    @Test
    @DisplayName("CONNECT 없이 30초 넘은 연결 · 토큰이 만료된 연결을 닫고, 정상 연결은 둔다 — 연결 수 지표도 함께")
    void sweepClosesUnauthenticatedAndExpired() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(T0);
        ChatSessionRegistry early = new ChatSessionRegistry(clock, meters, 5);
        WebSocketSession idle = session("idle");
        WebSocketSession expired = session("expired");
        WebSocketSession healthy = session("healthy");
        early.opened(idle);
        early.opened(expired);
        early.opened(healthy);
        early.authenticated("expired", principal(1L, T0.plusSeconds(60)));
        early.authenticated("healthy", principal(2L, T0.plusSeconds(3600)));
        assertThat(meters.get("chat.ws.sessions").gauge().value()).isEqualTo(3);

        early.sweep();   // 아직 30초 전 · 만료 전
        verify(idle, never()).close(any(CloseStatus.class));

        clock.now = T0.plusSeconds(61);
        early.sweep();

        verify(idle).close(ChatSessionRegistry.UNAUTHENTICATED);
        verify(expired).close(ChatSessionRegistry.TOKEN_EXPIRED);
        verify(healthy, never()).close(any(CloseStatus.class));
        assertThat(early.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 사용자 연결이 상한을 넘으면 가장 오래된 것부터 닫는다 — 다른 사용자 연결은 세지 않음")
    void capPerUser() throws Exception {
        ChatSessionRegistry registry = new ChatSessionRegistry(Clock.fixed(T0, ZoneId.of("UTC")), new SimpleMeterRegistry(), 2);
        WebSocketSession first = session("a");
        WebSocketSession second = session("b");
        WebSocketSession third = session("c");
        WebSocketSession other = session("d");
        for (WebSocketSession s : List.of(first, other, second, third)) {
            registry.opened(s);
        }
        registry.authenticated("a", principal(1L, null));
        registry.authenticated("d", principal(9L, null));
        registry.authenticated("b", principal(1L, null));
        registry.authenticated("c", principal(1L, null));

        verify(first).close(ChatSessionRegistry.TOO_MANY);
        verify(second, never()).close(any(CloseStatus.class));
        verify(other, never()).close(any(CloseStatus.class));
        registry.authenticated("gone", principal(1L, null));   // 이미 닫힌 연결은 무시
        registry.closed("b");
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("이미 닫힌 연결을 닫다 오류가 나도 목록에서는 빠진다")
    void closeFailureIsIgnored() throws Exception {
        ChatSessionRegistry registry = new ChatSessionRegistry(Clock.fixed(T0.plusSeconds(3600), ZoneId.of("UTC")), new SimpleMeterRegistry(), 5);
        WebSocketSession broken = session("x");
        willThrow(new IOException("closed")).given(broken).close(any(CloseStatus.class));
        registry.opened(broken);
        registry.authenticated("x", principal(1L, T0));

        registry.sweep();

        assertThat(registry.size()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        return session;
    }

    private static ChatPrincipal principal(Long userId, Instant expiresAt) {
        return new ChatPrincipal(userId, List.of("BUYER"), null, expiresAt);
    }
}
