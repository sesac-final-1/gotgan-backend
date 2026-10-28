package com.sesac.gojigi.product.product.dto.request;

import com.sesac.gojigi.product.product.entity.ProductSort;

/**
 * 공개 상품 목록 · 검색 조건 (product.md 9절) — 컨트롤러가 쿼리 파라미터를 하나씩 받아(@RequestParam) 만든다. 비어 있는 값은 조건 없음 · 기본값.
 * 형식 · 범위는 컨트롤러 파라미터에서, q 의 앞뒤 공백 제거 뒤 길이(50자) · minPrice ≤ maxPrice 는 서비스가 검사한다.
 * (객체로 받으면(@ModelAttribute) 형식 변환 실패 메시지가 "Failed to convert …" 그대로 응답에 나가서 파라미터로 받는다)
 */
public record ProductSearchRequest(
        Long categoryId, Long storeId, String q, Long minPrice, Long maxPrice,
        Boolean excludeSoldOut, ProductSort sort, Integer page, Integer size) {
}
