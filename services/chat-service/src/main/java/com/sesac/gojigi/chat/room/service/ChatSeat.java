package com.sesac.gojigi.chat.room.service;

import com.sesac.gojigi.chat.room.entity.SenderRole;

/**
 * 한 방에서 요청자가 앉은 자리 — 같은 사람도 방마다 다르다(판매자가 다른 스토어에 스토어 톡을 열면 그 방에선 BUYER).
 * readerKey: USER#{id} · STORE#{id}(판매자는 스토어 단위) · ADMIN(관리자 전원 공용). readOnly: 관리자의 '도움 요청' 스토어 톡 열람
 */
public record ChatSeat(SenderRole role, String readerKey, boolean readOnly) {

    static final String ADMIN_KEY = "ADMIN";

    static ChatSeat buyer(Long userId) {
        return new ChatSeat(SenderRole.BUYER, "USER#" + userId, false);
    }

    static ChatSeat seller(Long storeId) {
        return new ChatSeat(SenderRole.SELLER, "STORE#" + storeId, false);
    }

    static ChatSeat admin(boolean readOnly) {
        return new ChatSeat(SenderRole.ADMIN, ADMIN_KEY, readOnly);
    }
}
