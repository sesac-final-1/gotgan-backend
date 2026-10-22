package com.sesac.gojigi.user.point.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.point.dto.response.UserPointsResponse;
import com.sesac.gojigi.user.point.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** admin-service 전용 (관리자 회원 상세 포인트 탭) — 게이트웨이 라우트 없음, 클러스터 내부에서만 호출 */
@Tag(name = "회원 관리 (내부)", description = "admin-service 가 /api/admin/users 를 받아 호출 — 인가: 내부")
@RestController
@RequiredArgsConstructor
public class InternalUserPointController {

    private final PointService pointService;

    @Operation(summary = "회원 포인트 잔액 · 이력", description = """
            balance = 현재 잔액, history = 이력 최신순 서버 페이징 (page 0부터, size 1~100 기본 20).
            이력 항목은 내 포인트(GET /api/users/me/points)와 같음 — type: EARN · USE · RESTORE · RECLAIM, amount 는 항상 양수.
            에러: 400 INVALID_INPUT(page · size 범위) · 404 USER_NOT_FOUND(없는 회원 · 탈퇴)""")
    @GetMapping("/internal/users/{userId}/points")
    public ApiResponse<UserPointsResponse> getUserPoints(@PathVariable Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success("포인트를 조회했습니다.", pointService.getUserPoints(userId, page, size));
    }
}
