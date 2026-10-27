package com.sesac.gojigi.product.product.dto.response;

import java.util.List;

/** 판매중지 · 재개 결과 (product.md 15절) — 건마다 나눠 줌. 순서는 요청 순서(중복 제외) */
public record ProductStatusChangeResponse(List<Long> success, List<Failure> failed) {

    public record Failure(Long productId, String code, String message) {
    }
}
