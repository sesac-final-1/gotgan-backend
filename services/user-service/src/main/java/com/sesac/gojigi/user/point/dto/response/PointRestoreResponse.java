package com.sesac.gojigi.user.point.dto.response;

import com.sesac.gojigi.user.point.entity.PointHistory;

/**
 * 포인트 사용 취소(Saga 보상) 결과. 되돌릴 사용이 없었으면 restoredAmount 0 · balanceAfter null
 * (보상은 사용 단계가 실패했어도 불리므로 정상 응답).
 */
public record PointRestoreResponse(String sagaId, long restoredAmount, Long balanceAfter) {

    public static PointRestoreResponse from(PointHistory restore) {
        return new PointRestoreResponse(restore.getRefKey(), restore.getAmount(), restore.getBalanceAfter());
    }

    public static PointRestoreResponse nothingToRestore(String sagaId) {
        return new PointRestoreResponse(sagaId, 0L, null);
    }
}
