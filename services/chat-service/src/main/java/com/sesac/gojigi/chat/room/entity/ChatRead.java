package com.sesac.gojigi.chat.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 방마다 읽은 위치 — reader_key 는 USER#{id} · STORE#{id} · ADMIN. 쓰기는 ChatReadRepository.markRead 만 */
@Getter
@Entity
@Table(name = "chat_read")
@IdClass(ChatRead.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRead {

    @Id
    @Column(name = "chat_room_id")
    private Long chatRoomId;

    @Id
    @Column(name = "reader_key", length = 30)
    private String readerKey;

    @Column(name = "last_read_message_id", nullable = false)
    private Long lastReadMessageId;

    public record Key(Long chatRoomId, String readerKey) implements Serializable {
    }
}
