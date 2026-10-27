package com.sesac.gojigi.chat.realtime;

import com.sesac.gojigi.chat.room.dto.ChatResponses;
import com.sesac.gojigi.chat.room.entity.ChatMessage;
import com.sesac.gojigi.chat.room.entity.ChatRoomStatus;
import com.sesac.gojigi.chat.room.entity.MessageKind;
import com.sesac.gojigi.chat.room.entity.SenderRole;
import java.time.OffsetDateTime;

/**
 * 구독(/sub/rooms/{roomId})으로 나가는 프레임 본문 (chat.md 6절) — type 으로 구분.
 * 같은 프레임이 방의 모든 구독자에게 가므로 mine 은 없다 — 화면이 senderRole 과 자기 자리로 판단
 */
public final class RealtimePayloads {

    private RealtimePayloads() {
    }

    public record Message(String type, Long messageId, Long roomId, SenderRole senderRole, MessageKind kind, String content,
            ChatResponses.Context context, OffsetDateTime createdAt) {

        static Message of(ChatMessage m) {
            ChatResponses.Message view = ChatResponses.Message.of(m, null);
            return new Message("MESSAGE", view.messageId(), view.roomId(), view.senderRole(), view.kind(), view.content(),
                    view.context(), view.createdAt());
        }
    }

    public record Read(String type, SenderRole userRole, Long lastMessageId) {

        static Read of(SenderRole role, Long lastMessageId) {
            return new Read("READ", role, lastMessageId);
        }
    }

    public record Status(String type, ChatRoomStatus status) {

        static Status of(ChatRoomStatus status) {
            return new Status("STATUS", status);
        }
    }

    /** 입력 중 — 저장하지 않고 구독자에게만. 화면은 몇 초 동안 "입력 중…" 을 보여 준다 */
    public record Typing(String type, SenderRole userRole) {

        static Typing of(SenderRole role) {
            return new Typing("TYPING", role);
        }
    }

    /** /user/queue/errors — 보낸 연결에만. 연결은 유지된다 (토큰 · 권한 오류는 STOMP ERROR 후 끊김) */
    public record Error(String code, String message) {
    }
}
