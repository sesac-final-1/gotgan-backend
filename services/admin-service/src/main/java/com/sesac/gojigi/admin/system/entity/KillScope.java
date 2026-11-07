package com.sesac.gojigi.admin.system.entity;

/** 내리기 범위 — ONE: Ready 파드 1개(레플리카 2 면 끊김 없음) · ALL: 그 서비스 파드 전부(기능 중단) */
public enum KillScope {
    ONE,
    ALL
}
