package com.sesac.gojigi.chat.realtime;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * STOMP over WebSocket /ws/chat (chat.md 6 · 6-1절) — 연결은 항상 열려 있어서 수명과 자원을 여기서 묶는다.
 * <ul>
 *   <li>하트비트 10초(양방향) — 화면이 사라졌는데 닫기 신호가 없던 연결(모바일 절전 · 네트워크 끊김)은 브로커가 약 30초 안에 닫는다.
 *       NLB 유휴 350초보다 훨씬 짧아 중간 장비가 조용히 끊는 일도 없다</li>
 *   <li>CONNECT 첫 프레임은 {@value #FIRST_MESSAGE_MILLIS}ms 안에 — 연결만 열고 아무것도 안 보내는 연결을 바로 닫는다</li>
 *   <li>프레임 {@value #MESSAGE_SIZE_LIMIT}바이트(1,000자 한글 + 헤더) · 연결별 보내기 버퍼 {@value #SEND_BUFFER_LIMIT}바이트 · 보내기 {@value #SEND_TIME_LIMIT}ms —
 *       느린 연결 하나가 메모리 · 스레드를 붙잡으면 그 연결만 닫는다</li>
 *   <li>들어오고 나가는 채널 스레드는 고정 4개 — 연결 수와 무관 (연결 자체는 Tomcat NIO 라 스레드를 쓰지 않음)</li>
 *   <li>연결 목록(ChatSessionRegistry)에 열림 · 닫힘을 기록 — 미인증 · 토큰 만료 · 사용자당 상한 정리</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final long HEARTBEAT_MILLIS = 10_000;
    static final int FIRST_MESSAGE_MILLIS = 10_000;
    static final int MESSAGE_SIZE_LIMIT = 8 * 1024;
    static final int SEND_BUFFER_LIMIT = 256 * 1024;
    static final int SEND_TIME_LIMIT = 10_000;
    private static final int CHANNEL_THREADS = 4;

    private final StompAuthInterceptor stompAuthInterceptor;
    private final ChatSessionRegistry sessionRegistry;

    @Value("${app.chat.ws.allowed-origins}")
    private List<String> allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 게이트웨이도 Origin 을 보지만, 핸드셰이크 Origin 이 api 주소와 달라 여기서도 허용해야 한다 (기본은 같은 출처만)
        registry.addEndpoint("/ws/chat").setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("ws-heartbeat-");
        heartbeat.setDaemon(true);                                                       // 종료를 막지 않게 (빈이 아니라 스프링이 멈추지 않음)
        heartbeat.initialize();
        registry.enableSimpleBroker("/sub", "/queue")   // /sub/rooms/{id} · /user/queue/errors
                .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/pub");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT)
                .setSendBufferSizeLimit(SEND_BUFFER_LIMIT)
                .setSendTimeLimit(SEND_TIME_LIMIT)
                .setTimeToFirstMessage(FIRST_MESSAGE_MILLIS)
                .addDecoratorFactory(this::tracked);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
        registration.taskExecutor().corePoolSize(CHANNEL_THREADS).maxPoolSize(CHANNEL_THREADS);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(CHANNEL_THREADS).maxPoolSize(CHANNEL_THREADS);
    }

    private WebSocketHandler tracked(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionRegistry.opened(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessionRegistry.closed(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }
}
