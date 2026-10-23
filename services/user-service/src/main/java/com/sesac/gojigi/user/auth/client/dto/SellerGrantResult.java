package com.sesac.gojigi.user.auth.client.dto;

/** 판매자 전환(SELLER 역할 · store_id 속성) 결과 — KeycloakClientAdapter.grantSeller */
public enum SellerGrantResult {
    /** 역할 · 속성 중 하나 이상을 새로 넣음 */
    GRANTED,
    /** 이미 SELLER · 같은 store_id — 재전달 등 */
    ALREADY_GRANTED,
    /** 이미 다른 store_id 가 있음 — 1인 1스토어 위반, 아무것도 바꾸지 않음 */
    STORE_MISMATCH,
    /** Keycloak 계정 없음 — 바꿀 대상이 없음 */
    ACCOUNT_NOT_FOUND
}
