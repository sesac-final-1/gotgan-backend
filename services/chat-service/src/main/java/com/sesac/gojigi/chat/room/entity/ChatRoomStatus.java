package com.sesac.gojigi.chat.room.entity;

/** 상담방 상태 — 관리자가 RESOLVED 로 닫고, 상대가 새 메시지를 보내면 OPEN */
public enum ChatRoomStatus {
    OPEN,
    RESOLVED
}
