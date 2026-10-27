package com.sesac.gojigi.chat.room.entity;

/** 방 안에서의 쪽 — 판매자는 스토어 단위, 관리자는 전원 공용. SYSTEM 은 서버가 남긴 안내 */
public enum SenderRole {
    BUYER,
    SELLER,
    ADMIN,
    SYSTEM
}
