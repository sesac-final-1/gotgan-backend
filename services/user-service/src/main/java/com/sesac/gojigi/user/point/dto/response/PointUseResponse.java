package com.sesac.gojigi.user.point.dto.response;

import com.sesac.gojigi.user.point.entity.PointHistory;

/** 포인트 사용 결과 — balanceAfter 는 사용 직후 잔액 */
public record PointUseResponse(String sagaId, Long userId, String orderNo, long amount, long balanceAfter) {

    public static PointUseResponse from(PointHistory use) {
        return new PointUseResponse(use.getRefKey(), use.getUser().getId(), use.getOrderNo(), use.getAmount(),
                use.getBalanceAfter());
    }
}
