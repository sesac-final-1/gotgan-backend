package com.sesac.gojigi.user.point.dto.response;

import com.sesac.gojigi.user.point.entity.PointHistory;

/**
 * 포인트 환원 · 회수 결과. reclaimedAmount 는 실제로 뺀 포인트, shortfallAmount 는 잔액이 모자라 못 뺀 차액(잔액은 0 까지만).
 * 한 일이 없으면(둘 다 0 · 탈퇴 회원) 모두 0 · balanceAfter null.
 */
public record PointReversalResponse(Long claimId, Long orderItemId, long restoredAmount, long reclaimedAmount,
                                    long shortfallAmount, Long balanceAfter) {

    public static PointReversalResponse of(Long claimId, Long orderItemId, PointHistory restore, PointHistory reclaim) {
        PointHistory last = reclaim != null ? reclaim : restore;
        return new PointReversalResponse(claimId, orderItemId,
                restore == null ? 0L : restore.getAmount(),
                reclaim == null ? 0L : reclaim.getAmount(),
                reclaim == null ? 0L : reclaim.getShortfallAmt(),
                last == null ? null : last.getBalanceAfter());
    }
}
