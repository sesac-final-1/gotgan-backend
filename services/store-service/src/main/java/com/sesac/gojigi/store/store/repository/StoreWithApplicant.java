package com.sesac.gojigi.store.store.repository;

import com.sesac.gojigi.store.store.entity.Store;

/** 스토어 + 원래 신청서의 대표자 · 사업자번호 — store 테이블에는 두 값이 없다 (storedb V1 store.store_application_id 주석) */
public record StoreWithApplicant(Store store, String ownerName, String bizRegNo) {
}
