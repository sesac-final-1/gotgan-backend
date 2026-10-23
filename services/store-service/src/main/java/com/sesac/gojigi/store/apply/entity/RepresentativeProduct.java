package com.sesac.gojigi.store.apply.entity;

/**
 * 판매 예정 대표 상품 — 심사 화면 표시용이라 store_application.representative_products JSON 에 받은 모양 그대로 저장.
 * subCategory 는 세부 분류 이름 (id 가 아님 — product 조회 없이 보여 주기 위해, docs/api/store.md 10/22 설계 정리 3)
 */
public record RepresentativeProduct(String name, String subCategory, long price) {
}
