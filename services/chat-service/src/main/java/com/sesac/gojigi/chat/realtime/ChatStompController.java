package com.sesac.gojigi.chat.realtime;

import com.sesac.gojigi.chat.room.exception.ChatErrorCode;
import com.sesac.gojigi.chat.room.exception.ChatException;
import com.sesac.gojigi.chat.room.service.ChatRoomService;
import com.sesac.gojigi.common.web.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/** SEND /pub/rooms/{roomId} {content} — 저장 후 커밋되면 구독자에게(ChatRealtimeListener). 업무 오류는 보낸 연결의 /user/queue/errors 로, 연결은 유지 */
@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatRoomService chatRoomService;
    private final ChatRateLimiter rateLimiter;

    @MessageMapping("/rooms/{roomId}")
    public void send(@DestinationVariable Long roomId, @Payload SendRequest request, ChatPrincipal principal,
                     @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sessionId) {
        if (!rateLimiter.tryAcquire(sessionId)) {
            throw new ChatException(ChatErrorCode.CHAT_TOO_FAST);
        }
        chatRoomService.send(principal.toAuthUser(), roomId, request.content());
    }

    @MessageExceptionHandler(BusinessException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public RealtimePayloads.Error error(BusinessException e) {
        return new RealtimePayloads.Error(e.getErrorCode().getCode(), e.getMessage());
    }

    public record SendRequest(String content) {
    }
}
