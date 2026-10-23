package com.sesac.gojigi.user.point.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.point.dto.request.ReversePointRequest;
import com.sesac.gojigi.user.point.dto.request.UsePointRequest;
import com.sesac.gojigi.user.point.dto.response.PointRestoreResponse;
import com.sesac.gojigi.user.point.dto.response.PointReversalResponse;
import com.sesac.gojigi.user.point.dto.response.PointUseResponse;
import com.sesac.gojigi.user.point.dto.response.UserPointsResponse;
import com.sesac.gojigi.user.point.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 간 전용 (order-service Saga · admin-service) — 게이트웨이 라우트 없음, 클러스터 내부에서만 호출 */
@Tag(name = "포인트 (내부)", description = "order-service 주문 Saga · admin-service 회원 관리가 호출 — 인가: 내부")
@RestController
@RequiredArgsConstructor
public class InternalPointController {

    private final PointService pointService;

    @Operation(summary = "포인트 사용", description = """
            주문 생성 Saga 단계. sagaId 멱등 — 처음 201, 같은 요청 재전송 200(처음 결과 그대로).
            에러: 400 INVALID_INPUT(amount 1 미만 등) · 404 USER_NOT_FOUND · 409 POINT_INSUFFICIENT
            · 409 POINT_USE_CONFLICT(같은 sagaId 로 다른 회원 · 금액)""")
    @PostMapping("/internal/points/use")
    public ResponseEntity<ApiResponse<PointUseResponse>> use(@Valid @RequestBody UsePointRequest request) {
        PointService.UseResult result = pointService.use(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success("포인트를 사용했습니다.", result.response()));
    }

    @Operation(summary = "포인트 사용 취소 (Saga 보상)", description = """
            그 sagaId 로 쓴 포인트를 돌려준다. 멱등 — 이미 돌려줬으면 처음 결과 그대로.
            사용 기록이 없으면(사용 단계 실패 · 미호출) restoredAmount 0 으로 200 — 보상은 항상 성공으로 끝난다.""")
    @DeleteMapping("/internal/points/use/{sagaId}")
    public ApiResponse<PointRestoreResponse> cancelUse(@PathVariable String sagaId) {
        return ApiResponse.success("포인트 사용을 취소했습니다.", pointService.cancelUse(sagaId));
    }

    @Operation(summary = "취소 · 반품 포인트 환원 · 회수", description = """
            order 취소 · 반품 Saga 단계. restoreAmount 만큼 돌려주고(RESTORE), 이 상품주문으로 실제 적립된 만큼까지 reclaimAmount 를 회수(RECLAIM).
            회수는 잔액 0 까지만 — 못 뺀 차액은 shortfallAmount. 멱등 키 claim-{claimId}-{orderItemId} — 같은 요청 재전송은 처음 결과 그대로.
            탈퇴 회원이면 아무것도 하지 않고 0 으로 200.
            에러: 400 INVALID_INPUT(음수 금액 · 필수값 누락)""")
    @PostMapping("/internal/points/reversals")
    public ApiResponse<PointReversalResponse> reverse(@Valid @RequestBody ReversePointRequest request) {
        return ApiResponse.success("포인트를 환원 · 회수했습니다.", pointService.reverse(request));
    }

    @Operation(summary = "회원 포인트 잔액 · 이력 (관리자 회원 상세)", description = """
            admin-service 가 /api/admin/users/{id}/points 를 받아 호출.
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
