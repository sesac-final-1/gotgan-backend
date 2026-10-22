package com.sesac.gojigi.user.point.dto.response;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.user.point.entity.PointHistory;
import org.springframework.data.domain.Page;

/**
 * 관리자 회원 포인트 — 잔액 + 이력 한 페이지(최신순). 이력 항목은 내 포인트(MyPointsResponse.Item)와 같은 모양.
 */
public record UserPointsResponse(long balance, PageResponse<MyPointsResponse.Item> history) {

    public static UserPointsResponse of(long balance, Page<PointHistory> history) {
        return new UserPointsResponse(balance, PageResponse.from(history.map(MyPointsResponse.Item::from)));
    }
}
