package com.sesac.gojigi.user.point.dto.response;

import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 내 포인트 — 잔액 + 이력 전체(최신순). 화면은 한 번에 받아 탭 · 검색 · 페이징 (컨벤션 4-7).
 * amount 는 항상 양수 — 증감은 type 으로 (EARN · RESTORE 증가, USE · RECLAIM 감소).
 */
public record MyPointsResponse(long balance, List<Item> history) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record Item(Long id, PointType type, long amount, long balanceAfter, String orderNo, OffsetDateTime createdAt) {

        static Item from(PointHistory h) {
            return new Item(h.getId(), h.getType(), h.getAmount(), h.getBalanceAfter(), h.getOrderNo(),
                    h.getCreatedAt() == null ? null : h.getCreatedAt().atZone(KST).toOffsetDateTime());
        }
    }

    public static MyPointsResponse of(long balance, List<PointHistory> history) {
        return new MyPointsResponse(balance, history.stream().map(Item::from).toList());
    }
}
