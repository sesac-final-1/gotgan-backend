package com.sesac.gojigi.product.product.dto.response;

import java.util.List;

/** 재고 수정 결과 (product.md 14절) — 이번에 바꾼 옵션만, 옵션 ID 오름차순. 전체는 상세(12절)를 다시 읽는다 */
public record ProductStockUpdateResponse(List<Option> options) {

    public record Option(Long productOptionId, int stock) {
    }
}
