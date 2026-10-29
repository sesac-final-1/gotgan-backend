package com.sesac.gojigi.chat.realtime;

import com.sesac.gojigi.chat.room.service.ChatRoomService;
import com.sesac.gojigi.chat.room.service.ChatSeat;
import com.sesac.gojigi.common.web.exception.BusinessException;
import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * 들어오는 STOMP 프레임 검사 (chat.md 6절). 여기서 던지면 STOMP ERROR 프레임이 나가고 화면은 연결을 닫는다.
 * <ul>
 *   <li>CONNECT — Authorization: Bearer 토큰을 Keycloak JWKS 로 검증 · 탈퇴 차단 확인 → 연결 사용자(ChatPrincipal). 거절이면 서버가 곧 닫음</li>
 *   <li>SUBSCRIBE — /sub/rooms/{id} 는 방 멤버만(관리자 읽기 전용 포함) · /user/queue/errors 만 그 밖에 허용. 구독하면 접속 중 표시</li>
 *   <li>SEND — 토큰이 만료됐으면 거절(다시 연결해 새 토큰으로) · /pub 밖(브로커 주소 /sub · /user)은 거절 — 멤버 검사 · 저장 없이 구독자에게 바로 가지 않게</li>
 *   <li>UNSUBSCRIBE — 접속 중 표시 해제 (연결 끊김은 ChatSessionEvents)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    static final String ERRORS_DESTINATION = "/user/queue/errors";
    private static final Pattern ROOM_DESTINATION = Pattern.compile("^" + ChatRelay.DESTINATION_PREFIX + "(\\d{1,18})$");
    private static final String BEARER = "Bearer ";
    private static final String APP_PREFIX = "/pub/";   // WebSocketConfig 의 application destination prefix

    private final JwtDecoder jwtDecoder;
    private final BlockedUsers blockedUsers;
    private final ChatRoomService chatRoomService;
    private final ChatSessionRegistry sessions;
    private final ChatPresence presence;
    private final Clock clock;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> connect(accessor);
            case SUBSCRIBE -> subscribe(accessor);
            case SEND -> send(accessor);
            case UNSUBSCRIBE -> presence.leave(accessor.getSessionId(), accessor.getSubscriptionId());
            default -> {
                // DISCONNECT 등은 그대로 — 정리는 SessionDisconnectEvent 에서 한 번에
            }
        }
        return message;
    }

    private void connect(StompHeaderAccessor accessor) {
        try {
            ChatPrincipal principal = verify(accessor);
            accessor.setUser(principal);
            sessions.authenticated(accessor.getSessionId(), principal);
        } catch (MessagingException e) {
            sessions.rejected(accessor.getSessionId());
            throw e;
        }
    }

    private ChatPrincipal verify(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw new MessagingException("UNAUTHORIZED: 토큰이 필요합니다.");
        }
        ChatPrincipal principal;
        try {
            principal = ChatPrincipal.from(jwtDecoder.decode(header.substring(BEARER.length())));
        } catch (JwtException e) {
            log.debug("채팅 연결 토큰 거절 session={}, cause={}", accessor.getSessionId(), e.getMessage());
            throw new MessagingException("UNAUTHORIZED: 토큰이 올바르지 않거나 만료됐습니다.");
        }
        if (principal == null || principal.expired(clock.instant())) {
            throw new MessagingException("UNAUTHORIZED: 회원 정보가 없는 토큰입니다.");
        }
        if (blockedUsers.isBlocked(principal.userId())) {
            throw new MessagingException("USER_BLOCKED: 탈퇴했거나 이용이 제한된 계정입니다.");
        }
        return principal;
    }

    private void subscribe(StompHeaderAccessor accessor) {
        ChatPrincipal principal = principal(accessor);
        String destination = accessor.getDestination();
        if (ERRORS_DESTINATION.equals(destination)) {
            return;
        }
        Matcher room = destination == null ? null : ROOM_DESTINATION.matcher(destination);
        if (room == null || !room.matches()) {
            throw new MessagingException("CHAT_NOT_ALLOWED: 구독할 수 없는 주소입니다.");
        }
        Long roomId = Long.valueOf(room.group(1));
        ChatSeat seat;
        try {
            seat = chatRoomService.seat(principal.toAuthUser(), roomId);
        } catch (BusinessException e) {
            throw new MessagingException(e.getErrorCode().getCode() + ": " + e.getMessage());
        }
        presence.enter(accessor.getSessionId(), accessor.getSubscriptionId(), roomId, seat.readerKey());
    }

    private void send(StompHeaderAccessor accessor) {
        principal(accessor);
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(APP_PREFIX)) {
            throw new MessagingException("CHAT_NOT_ALLOWED: 보낼 수 없는 주소입니다.");
        }
    }

    private ChatPrincipal principal(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof ChatPrincipal principal)) {
            throw new MessagingException("UNAUTHORIZED: 먼저 연결(CONNECT)해 주세요.");
        }
        if (principal.expired(clock.instant())) {
            throw new MessagingException("UNAUTHORIZED: 토큰이 만료됐습니다. 다시 연결해 주세요.");
        }
        return principal;
    }
}
