package com.sesac.gojigi.store.apply.repository;

/** 내부 상세(store.md 1절)에 필요한 신청서 값만 — 소개 · 대표 상품 · 계좌 같은 칸은 읽지 않는다 */
public record ApplicantInfo(String ownerName, String companyName, String bizRegNo) {
}
