package com.sesac.gojigi.chat.room.dto;

import com.sesac.gojigi.chat.room.entity.ChatRoomStatus;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import com.sesac.gojigi.chat.room.entity.ContextType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 채팅 요청 본문 (chat.md 1 · 4 · 5절) */
public final class ChatRequests {

    private ChatRequests() {
    }

    /** POST /api/chats/rooms — storeId 는 스토어 톡 · 관리자의 판매자 상담에서만 */
    public record OpenRoom(@NotNull ChatRoomType type, @Positive Long storeId, @Valid Context context) {
    }

    public record Context(@NotNull ContextType type, @NotNull @Positive Long id) {
    }

    /** PATCH .../read */
    public record Read(@NotNull @Positive Long lastMessageId) {
    }

    /** PATCH .../status (ADMIN) */
    public record Status(@NotNull ChatRoomStatus status) {
    }
}
