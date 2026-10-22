package com.sesac.gojigi.order.order.entity;

/** 상태를 바꾼 주체 — SYSTEM(스케줄러 · Saga)이면 actor_id 는 NULL */
public enum ActorType {
    BUYER,
    SELLER,
    ADMIN,
    SYSTEM
}
