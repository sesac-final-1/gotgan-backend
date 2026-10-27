package com.sesac.gojigi.chat.room.entity;

/** 방 종류 (chat.md "방 3종류") — 상담방 2종만 상태(OPEN · RESOLVED)가 있다 */
public enum ChatRoomType {
    STORE_INQUIRY,   // 스토어 톡 — 구매자 ↔ 스토어
    SELLER_SUPPORT,  // 판매자 운영 상담 — 스토어 ↔ 관리자 전원
    BUYER_SUPPORT;   // 고객센터 1:1 상담 — 구매자 ↔ 관리자 전원

    public boolean isSupport() {
        return this != STORE_INQUIRY;
    }
}
