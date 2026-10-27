package com.sesac.gojigi.chat.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** 채팅 메시지 — 수정 · 삭제 없음, 90일 뒤 보관 삭제(ChatRetentionJob). created_at 의 주인은 DB */
@Getter
@Entity
@Table(name = "chat_message")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_message_id")
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    @Column(name = "sender_id")
    private Long senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 10)
    private SenderRole senderRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private MessageKind kind;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "context_type", length = 20)
    private ContextType contextType;

    @Column(name = "context_id")
    private Long contextId;

    @Column(name = "context_title", length = 200)
    private String contextTitle;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private ChatMessage(Long chatRoomId, Long senderId, SenderRole senderRole, MessageKind kind, String content) {
        this.chatRoomId = chatRoomId;
        this.senderId = senderId;
        this.senderRole = senderRole;
        this.kind = kind;
        this.content = content;
    }

    /** 문맥 카드 — 내용은 카드 제목(목록 미리보기용) */
    public static ChatMessage context(Long chatRoomId, Long senderId, SenderRole senderRole, ContextType type, Long id, String title) {
        ChatMessage message = new ChatMessage(chatRoomId, senderId, senderRole, MessageKind.CONTEXT, title);
        message.contextType = type;
        message.contextId = id;
        message.contextTitle = title;
        return message;
    }

    public static ChatMessage system(Long chatRoomId, String content) {
        return new ChatMessage(chatRoomId, null, SenderRole.SYSTEM, MessageKind.SYSTEM, content);
    }

    public boolean sameContext(ContextType type, Long id) {
        return contextType == type && id.equals(contextId);
    }
}
