package com.sesac.gojigi.chat.room.entity;

import com.sesac.gojigi.chat.common.entity.BaseTimeEntity;
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

/** 채팅방 — (type, buyer_id, store_id) 당 1개(uk_chat_room). 쓰지 않는 쪽 id 는 0 */
@Getter
@Entity
@Table(name = "chat_room")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseTimeEntity {

    /** buyer_id · store_id 를 쓰지 않는 방의 값 (UNIQUE 가 NULL 을 무시하지 않게) */
    public static final long NONE = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ChatRoomType type;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private ChatRoomStatus status;

    @Column(name = "last_message_id")
    private Long lastMessageId;

    @Column(name = "last_message_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime lastMessageAt;

    private ChatRoom(ChatRoomType type, long buyerId, long storeId) {
        this.type = type;
        this.buyerId = buyerId;
        this.storeId = storeId;
        this.status = type.isSupport() ? ChatRoomStatus.OPEN : null;
    }

    public static ChatRoom open(ChatRoomType type, long buyerId, long storeId) {
        return new ChatRoom(type, buyerId, storeId);
    }

    /** 새 메시지가 저장된 뒤 — 목록 정렬 · 미리보기 */
    public void touch(ChatMessage message) {
        this.lastMessageId = message.getId();
        this.lastMessageAt = message.getCreatedAt();
    }

    /** 상담방만 (스토어 톡은 호출 전에 막는다) */
    public void changeStatus(ChatRoomStatus status) {
        this.status = status;
    }
}
