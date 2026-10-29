package com.sesac.gojigi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import com.sesac.gojigi.chat.TestcontainersConfiguration;
import com.sesac.gojigi.chat.room.dto.ChatRequests;
import com.sesac.gojigi.chat.room.entity.ChatRoomStatus;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import com.sesac.gojigi.chat.room.entity.ContextType;
import com.sesac.gojigi.chat.room.service.ChatRoomService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP 연결 수명 · 권한 · 전달 (chat.md 6 · 6-1절) — 실제 포트에 WebSocket 으로 붙는다(local 모드, Redis 없음).
 * 토큰 검증(JwtDecoder)만 가짜 — 토큰 문자열 → 클레임. 트레이스 샘플링 0 — LogbackConfigTest 에 Zipkin 경고가 섞이지 않게
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=0")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ChatWebSocketTest {

    private static final Instant FAR = Instant.parse("2099-01-01T00:00:00Z");

    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatPresence presence;
    @Autowired ChatSessionRegistry sessions;
    @Autowired ChatRateLimiter rateLimiter;
    @MockitoBean JwtDecoder jwtDecoder;

    private WebSocketStompClient client;
    private final List<StompSession> opened = new java.util.ArrayList<>();
    private ThreadPoolTaskScheduler heartbeat;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM chat_read");
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_room");
        willThrow(new BadJwtException("bad")).given(jwtDecoder).decode(anyString());
        token("buyer", Map.of("user_id", 10L, "roles", List.of("default-roles-gotgan", "BUYER")), FAR);
        token("seller", Map.of("user_id", 20L, "store_id", 5L, "roles", List.of("BUYER", "SELLER")), FAR);
        token("admin", Map.of("user_id", 1L, "roles", List.of("ADMIN")), FAR);
        token("stranger", Map.of("user_id", 11L, "roles", List.of("BUYER")), FAR);
        token("unlinked", Map.of("roles", List.of("BUYER")), FAR);
        heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.initialize();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JsonAsString());
        client.setTaskScheduler(heartbeat);
        client.setDefaultHeartbeat(new long[] {10_000, 10_000});
    }

    /** 연결은 테스트마다 정리 — 서버 연결 목록이 비어야 다음 테스트가 깨끗하다 */
    @AfterEach
    void tearDown() {
        opened.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        opened.clear();
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions.size() == 0);
        if (heartbeat != null) {
            heartbeat.shutdown();
        }
    }

    @Test
    @DisplayName("보낸 메시지가 같은 방 구독자에게 — 공백 · 연속 개행 정리, 판매자가 보고 있으면 접속 중 표시, 끊으면 바로 지워짐")
    void sendDeliversToSubscribersAndCleansUpOnDisconnect() throws Exception {
        long roomId = storeInquiry();
        Client buyer = connect("buyer");
        Client seller = connect("seller");
        BlockingQueue<String> sellerFrames = seller.subscribe(ChatRelay.DESTINATION_PREFIX + roomId);
        BlockingQueue<String> buyerFrames = buyer.subscribe(ChatRelay.DESTINATION_PREFIX + roomId);
        await().until(() -> presence.isWatching(roomId, "STORE#5") && presence.isWatching(roomId, "USER#10"));

        buyer.send(roomId, "  안녕하세요\n\n\n\n문의 드려요  ");

        JsonNode frame = json.readTree(sellerFrames.poll(5, TimeUnit.SECONDS));
        assertThat(frame.path("type").asString()).isEqualTo("MESSAGE");
        assertThat(frame.path("content").asString()).isEqualTo("안녕하세요\n\n문의 드려요");
        assertThat(frame.path("senderRole").asString()).isEqualTo("BUYER");
        assertThat(buyerFrames.poll(5, TimeUnit.SECONDS)).contains("MESSAGE");

        seller.session().disconnect();
        await().atMost(Duration.ofSeconds(5)).until(() -> !presence.isWatching(roomId, "STORE#5"));
        assertThat(presence.isWatching(roomId, "USER#10")).isTrue();
        buyer.session().disconnect();
        await().atMost(Duration.ofSeconds(5)).until(() -> presence.localCount() == 0 && sessions.size() == 0 && rateLimiter.size() == 0);
    }

    @Test
    @DisplayName("읽음 · 상담 상태도 구독자에게 — 닫힌 상담방에 구매자가 보내면 다시 OPEN")
    void readAndStatusFrames() throws Exception {
        long roomId = chatRoomService.open(user(10L, "BUYER"), new ChatRequests.OpenRoom(ChatRoomType.BUYER_SUPPORT, null, null))
                .room().roomId();
        Client buyer = connect("buyer");
        BlockingQueue<String> frames = buyer.subscribe(ChatRelay.DESTINATION_PREFIX + roomId);

        chatRoomService.changeStatus(user(1L, "ADMIN"), roomId, ChatRoomStatus.RESOLVED);
        assertThat(json.readTree(frames.poll(5, TimeUnit.SECONDS)).path("status").asString()).isEqualTo("RESOLVED");
        JsonNode notice = json.readTree(frames.poll(5, TimeUnit.SECONDS));
        assertThat(notice.path("senderRole").asString()).isEqualTo("SYSTEM");

        chatRoomService.markRead(user(1L, "ADMIN"), roomId, notice.path("messageId").asLong());
        JsonNode read = json.readTree(frames.poll(5, TimeUnit.SECONDS));
        assertThat(read.path("type").asString()).isEqualTo("READ");
        assertThat(read.path("userRole").asString()).isEqualTo("ADMIN");

        buyer.send(roomId, "아직 해결이 안 됐어요");
        assertThat(json.readTree(frames.poll(5, TimeUnit.SECONDS)).path("status").asString()).isEqualTo("OPEN");
        assertThat(json.readTree(frames.poll(5, TimeUnit.SECONDS)).path("type").asString()).isEqualTo("MESSAGE");
        buyer.session().disconnect();
    }

    @Test
    @DisplayName("입력 중: 같은 방 구독자에게 TYPING(저장 안 함) — 1초에 한 번만 전달, 읽기 전용 관리자는 무시")
    void typingIsRelayedAndThrottled() throws Exception {
        long roomId = storeInquiry();
        Client buyer = connect("buyer");
        Client seller = connect("seller");
        BlockingQueue<String> sellerFrames = seller.subscribe(ChatRelay.DESTINATION_PREFIX + roomId);
        await().until(() -> presence.isWatching(roomId, "STORE#5"));

        buyer.typing(roomId);
        buyer.typing(roomId);   // 1초 안의 두 번째는 버림

        JsonNode frame = json.readTree(sellerFrames.poll(5, TimeUnit.SECONDS));
        assertThat(frame.path("type").asString()).isEqualTo("TYPING");
        assertThat(frame.path("userRole").asString()).isEqualTo("BUYER");
        assertThat(sellerFrames.poll(1_500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();

        chatRoomService.open(user(10L, "BUYER"), new ChatRequests.OpenRoom(ChatRoomType.BUYER_SUPPORT, null,
                new ChatRequests.Context(ContextType.CHAT_ROOM, roomId)));
        connect("admin").typing(roomId);
        assertThat(sellerFrames.poll(1_500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("토큰 없음 · 잘못된 토큰 · 회원 연결 없는 토큰은 CONNECT 에서 ERROR")
    void connectRequiresValidToken() throws Exception {
        assertThat(connectError(null)).contains("UNAUTHORIZED");
        assertThat(connectError("garbage")).contains("UNAUTHORIZED");
        assertThat(connectError("unlinked")).contains("UNAUTHORIZED");
        token("expired", Map.of("user_id", 10L, "roles", List.of("BUYER")), Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(connectError("expired")).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("남의 방 · 모르는 주소 구독은 ERROR, 관리자는 도움 요청된 스토어 톡만 읽기 전용 — 보내면 오류 큐로(연결 유지)")
    void subscribeAndSendPermissions() throws Exception {
        long roomId = storeInquiry();
        assertThat(subscribeError("stranger", ChatRelay.DESTINATION_PREFIX + roomId)).contains("CHAT_ROOM_NOT_FOUND");
        assertThat(subscribeError("buyer", "/sub/rooms/abc")).contains("CHAT_NOT_ALLOWED");
        assertThat(subscribeError("buyer", "/topic/anything")).contains("CHAT_NOT_ALLOWED");
        assertThat(subscribeError("admin", ChatRelay.DESTINATION_PREFIX + roomId)).contains("CHAT_ROOM_NOT_FOUND");

        chatRoomService.open(user(10L, "BUYER"), new ChatRequests.OpenRoom(ChatRoomType.BUYER_SUPPORT, null,
                new ChatRequests.Context(ContextType.CHAT_ROOM, roomId)));
        Client admin = connect("admin");
        BlockingQueue<String> errors = admin.subscribe(StompAuthInterceptor.ERRORS_DESTINATION);
        admin.subscribe(ChatRelay.DESTINATION_PREFIX + roomId);
        admin.send(roomId, "관리자가 끼어들기");

        assertThat(errors.poll(5, TimeUnit.SECONDS)).contains("CHAT_NOT_ALLOWED");
        assertThat(admin.session().isConnected()).isTrue();
        admin.session().disconnect();
    }

    @Test
    @DisplayName("/pub 밖(브로커 주소 /sub · /user)으로 SEND 하면 ERROR — 남의 방 구독자에게 검사 · 저장 없이 전달되지 않음")
    void sendOutsideAppPrefixIsRejected() throws Exception {
        long roomId = storeInquiry();
        Client seller = connect("seller");
        BlockingQueue<String> sellerFrames = seller.subscribe(ChatRelay.DESTINATION_PREFIX + roomId);
        await().until(() -> presence.isWatching(roomId, "STORE#5"));

        assertThat(sendError("stranger", ChatRelay.DESTINATION_PREFIX + roomId)).contains("CHAT_NOT_ALLOWED");
        assertThat(sendError("stranger", "/user/20/queue/errors")).contains("CHAT_NOT_ALLOWED");
        assertThat(sellerFrames.poll(1_000, TimeUnit.MILLISECONDS)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
    }

    @Test
    @DisplayName("1초 5건 넘게 보내면 CHAT_TOO_FAST · 빈 메시지는 INVALID_INPUT — 둘 다 오류 큐로")
    void rateLimitAndValidation() throws Exception {
        long roomId = storeInquiry();
        Client buyer = connect("buyer");
        BlockingQueue<String> errors = buyer.subscribe(StompAuthInterceptor.ERRORS_DESTINATION);

        buyer.send(roomId, "   ");
        assertThat(errors.poll(5, TimeUnit.SECONDS)).contains("INVALID_INPUT");
        for (int i = 0; i < ChatRateLimiter.LIMIT + 1; i++) {
            buyer.send(roomId, "메시지 " + i);
        }
        assertThat(errors.poll(5, TimeUnit.SECONDS)).contains("CHAT_TOO_FAST");
        buyer.session().disconnect();
    }

    @Test
    @DisplayName("한 사용자 연결이 상한(5)을 넘으면 가장 오래된 연결을 서버가 닫는다")
    void tooManySessionsClosesOldest() throws Exception {
        Client oldest = connect("buyer");
        for (int i = 0; i < 5; i++) {
            connect("buyer");
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> !oldest.session().isConnected());
        assertThat(opened.stream().filter(StompSession::isConnected).count()).isEqualTo(5);
    }

    // ── 도우미 ──

    private long storeInquiry() {
        return chatRoomService.open(user(10L, "BUYER"), new ChatRequests.OpenRoom(ChatRoomType.STORE_INQUIRY, 5L, null))
                .room().roomId();
    }

    private void token(String value, Map<String, Object> claims, Instant expiresAt) {
        Jwt jwt = Jwt.withTokenValue(value).header("alg", "RS256").claims(c -> c.putAll(claims))
                .issuedAt(Instant.parse("2020-01-01T00:00:00Z").minusSeconds(1)).expiresAt(expiresAt).build();
        willReturn(jwt).given(jwtDecoder).decode(value);
    }

    private static AuthUser user(Long userId, String role) {
        return new AuthUser(userId, List.of(role), null);
    }

    private Client connect(String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/chat", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        opened.add(session);
        return new Client(session);
    }

    /** ERROR 프레임 내용 — CONNECT 가 거절되면 연결 완료(CONNECTED)가 오지 않는다 */
    private String connectError(String token) throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        var future = client.connectAsync("ws://localhost:" + port + "/ws/chat", new WebSocketHttpHeaders(), headers, errorCollector(errors));
        String error = errors.poll(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS)).isNotNull();
        return error;
    }

    private String subscribeError(String token, String destination) throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/chat", new WebSocketHttpHeaders(), headers,
                errorCollector(errors)).get(5, TimeUnit.SECONDS);
        opened.add(session);
        session.subscribe(destination, new Collector(new LinkedBlockingQueue<>()));
        return errors.poll(5, TimeUnit.SECONDS);
    }

    /** 위조 메시지 프레임을 destination 으로 직접 SEND — 막히면 ERROR 프레임 내용 */
    private String sendError(String token, String destination) throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/chat", new WebSocketHttpHeaders(), headers,
                errorCollector(errors)).get(5, TimeUnit.SECONDS);
        opened.add(session);
        StompHeaders send = new StompHeaders();
        send.setDestination(destination);
        send.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        session.send(send, json.writeValueAsString(Map.of("type", "MESSAGE", "content", "위조", "senderRole", "SELLER")));
        return errors.poll(5, TimeUnit.SECONDS);
    }

    private static StompSessionHandlerAdapter errorCollector(BlockingQueue<String> errors) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession s, StompCommand command, StompHeaders headers, byte[] payload, Throwable e) {
                errors.add(String.valueOf(e.getMessage()));
            }

            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(headers.getFirst("message") + " " + payload);
            }
        };
    }

    /** 화면처럼 JSON 문자열을 주고받는다 (content-type application/json · text/plain 모두 문자열로) */
    private static final class JsonAsString extends StringMessageConverter {
        JsonAsString() {
            addSupportedMimeTypes(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        }
    }

    private final class Client {

        private final StompSession session;

        Client(StompSession session) {
            this.session = session;
        }

        StompSession session() {
            return session;
        }

        BlockingQueue<String> subscribe(String destination) {
            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            session.subscribe(destination, new Collector(frames));
            return frames;
        }

        void typing(long roomId) {
            StompHeaders headers = new StompHeaders();
            headers.setDestination("/pub/rooms/" + roomId + "/typing");
            session.send(headers, "{}");
        }

        void send(long roomId, String content) {
            StompHeaders headers = new StompHeaders();
            headers.setDestination("/pub/rooms/" + roomId);
            headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
            session.send(headers, json.writeValueAsString(Map.of("content", content)));
        }
    }

    private record Collector(BlockingQueue<String> frames) implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            frames.add((String) payload);
        }
    }
}
