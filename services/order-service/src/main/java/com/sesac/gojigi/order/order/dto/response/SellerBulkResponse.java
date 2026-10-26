package com.sesac.gojigi.order.order.dto.response;

import java.util.List;

/**
 * 판매자 일괄 처리 결과 — 상품주문마다 따로 처리해 일부만 실패할 수 있다.
 * succeeded: 바뀐 상품주문(택배사 · 송장번호 · 발송/배송완료 시각 포함), failed: 못 바꾼 상품주문과 사유(code 는 에러 코드).
 */
public record SellerBulkResponse(List<OrderItemResponse> succeeded, List<Failure> failed) {

    public record Failure(Long orderItemId, String code, String message) {
    }
}
