package com.sesac.gojigi.store.apply.entity;

/** 신청 상태를 바꾼 쪽 — 관리자 ID 는 남기지 않는다 (storedb application_history.actor_type) */
public enum ActorType {
    APPLICANT,
    ADMIN,
    SYSTEM
}
