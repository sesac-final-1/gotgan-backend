package com.sesac.gojigi.admin.report.entity;

/** 신고 상태 — OPEN 만 처리 대상, 관리자 조치 한 번에 대상의 열린 신고가 모두 닫힌다 */
public enum ReportStatus {
    OPEN,
    RESOLVED,
    DISMISSED
}
