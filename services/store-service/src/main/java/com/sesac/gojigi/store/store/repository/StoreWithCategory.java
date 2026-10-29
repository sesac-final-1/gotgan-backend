package com.sesac.gojigi.store.store.repository;

import com.sesac.gojigi.store.store.entity.Store;

/** 스토어 + 원래 신청서의 카테고리 — store 테이블에는 카테고리가 없다 (구매자 화면 카테고리 탭용) */
public record StoreWithCategory(Store store, Long categoryId) {
}
