package com.sesac.gojigi.chat.room.dto;

import com.sesac.gojigi.chat.room.entity.ChatMessage;
import com.sesac.gojigi.chat.room.entity.ChatRoom;
import com.sesac.gojigi.chat.room.entity.ChatRoomStatus;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import com.sesac.gojigi.chat.room.entity.ContextType;
import com.sesac.gojigi.chat.room.entity.MessageKind;
import com.sesac.gojigi.chat.room.entity.SenderRole;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 채팅 응답 (chat.md 1 · 3 · 4절) — 시각은 +09:00 */
public final class ChatResponses {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int PREVIEW_LENGTH = 40;

    private ChatResponses() {
    }

    /** 방 — storeId · buyerId 는 쓰지 않는 방이면 null. status 는 상담방만 */
    public record Room(Long roomId, ChatRoomType type, ChatRoomStatus status, Counterpart counterpart, Long storeId,
            Long buyerId, LastMessage lastMessage, long unreadCount, OffsetDateTime createdAt) {

        public static Room of(ChatRoom room, Counterpart counterpart, ChatMessage last, long unreadCount) {
            LastMessage lastMessage = room.getLastMessageAt() == null ? null
                    : new LastMessage(last == null ? null : preview(last.getContent()), kst(room.getLastMessageAt()));
            return new Room(room.getId(), room.getType(), room.getStatus(), counterpart, idOrNull(room.getStoreId()),
                    idOrNull(room.getBuyerId()), lastMessage, unreadCount, kst(room.getCreatedAt()));
        }
    }

    /** 상대 — 관리자 쪽은 "곳간 운영팀" 고정(D-2) */
    public record Counterpart(String name, SenderRole role) {
    }

    /** preview 는 보관 삭제로 메시지가 지워졌으면 null */
    public record LastMessage(String preview, OffsetDateTime at) {
    }

    public record Message(Long messageId, Long roomId, SenderRole senderRole, boolean mine, MessageKind kind, String content,
            Context context, OffsetDateTime createdAt) {

        public static Message of(ChatMessage m, SenderRole viewerRole) {
            Context context = m.getContextType() == null ? null
                    : new Context(m.getContextType(), m.getContextId(), m.getContextTitle());
            return new Message(m.getId(), m.getChatRoomId(), m.getSenderRole(), m.getSenderRole() == viewerRole, m.getKind(),
                    m.getContent(), context, kst(m.getCreatedAt()));
        }
    }

    public record Context(ContextType type, Long id, String title) {
    }

    public record UnreadCount(long count) {
    }

    /** 방 열기 결과 — created 면 201 */
    public record Opened(Room room, boolean created) {
    }

    private static Long idOrNull(Long id) {
        return id == null || id == ChatRoom.NONE ? null : id;
    }

    private static String preview(String content) {
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH);
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
