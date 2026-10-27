package com.sesac.gojigi.chat.room.service;

import com.sesac.gojigi.chat.room.entity.ChatMessage;
import com.sesac.gojigi.chat.room.entity.ChatRoom;
import com.sesac.gojigi.chat.room.entity.ChatRoomStatus;
import com.sesac.gojigi.chat.room.entity.SenderRole;

/** 방에서 일어난 일 — 커밋 뒤 실시간 전달 · 알림(realtime.ChatRealtimeListener). 커밋 전 내용이 화면에 나가지 않게 이벤트로 넘긴다 */
public final class ChatEvents {

    private ChatEvents() {
    }

    public record MessageSaved(ChatRoom room, ChatMessage message) {
    }

    public record ReadMoved(Long roomId, SenderRole role, Long lastMessageId) {
    }

    public record StatusChanged(Long roomId, ChatRoomStatus status) {
    }
}
