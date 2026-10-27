package com.sesac.gojigi.chat.room.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 문맥 카드 대상 (chat.md "문맥 카드"). 대상 서비스 조회 전 · 실패 시 카드 제목은 "{label} #{id}" */
@Getter
@RequiredArgsConstructor
public enum ContextType {
    PRODUCT("상품"),
    ORDER("주문"),
    ORDER_ITEM("상품주문"),
    SETTLEMENT("정산"),
    STORE("스토어"),
    REPORT("신고"),
    CHAT_ROOM("스토어 톡");   // 스토어 톡 '운영팀에 도움 요청' — 관리자가 그 방을 읽기 전용으로 볼 수 있다

    private final String label;

    public String fallbackTitle(Long id) {
        return label + " #" + id;
    }
}
