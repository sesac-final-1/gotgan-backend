package com.sesac.gojigi.user.point.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.point.dto.response.MyPointsResponse;
import com.sesac.gojigi.user.point.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 포인트 — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 (/api/users/** 인증 필요) */
@Tag(name = "포인트", description = "내 포인트 잔액 · 이력 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequestMapping("/api/users/me/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @Operation(summary = "내 포인트 잔액 · 이력", description = """
            인가: 로그인 회원 본인. 이력은 전체를 최신순으로 — 화면에서 구분 탭 · 검색 · 페이징 (컨벤션 4-7).
            type: EARN(적립) · USE(사용) · RESTORE(사용 환원) · RECLAIM(적립 회수). amount 는 항상 양수 — 증감은 type 으로.
            주문서의 포인트 사용 영역도 이 잔액을 쓴다. 에러: 401 UNAUTHORIZED · 404 USER_NOT_FOUND(탈퇴)""")
    @GetMapping
    public ApiResponse<MyPointsResponse> getMyPoints(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("포인트를 조회했습니다.", pointService.getMyPoints(user.userId()));
    }
}
