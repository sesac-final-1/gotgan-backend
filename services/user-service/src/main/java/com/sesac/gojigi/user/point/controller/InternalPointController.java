package com.sesac.gojigi.user.point.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.point.dto.request.UsePointRequest;
import com.sesac.gojigi.user.point.dto.response.PointRestoreResponse;
import com.sesac.gojigi.user.point.dto.response.PointUseResponse;
import com.sesac.gojigi.user.point.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** order-service(Saga) 전용 — 게이트웨이 라우트 없음, 클러스터 내부에서만 호출 */
@Tag(name = "포인트 (내부)", description = "order-service 주문 Saga 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/points/use")
@RequiredArgsConstructor
public class InternalPointController {

    private final PointService pointService;

    @Operation(summary = "포인트 사용", description = """
            주문 생성 Saga 단계. sagaId 멱등 — 처음 201, 같은 요청 재전송 200(처음 결과 그대로).
            에러: 400 INVALID_INPUT(amount 1 미만 등) · 404 USER_NOT_FOUND · 409 POINT_INSUFFICIENT
            · 409 POINT_USE_CONFLICT(같은 sagaId 로 다른 회원 · 금액)""")
    @PostMapping
    public ResponseEntity<ApiResponse<PointUseResponse>> use(@Valid @RequestBody UsePointRequest request) {
        PointService.UseResult result = pointService.use(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success("포인트를 사용했습니다.", result.response()));
    }

    @Operation(summary = "포인트 사용 취소 (Saga 보상)", description = """
            그 sagaId 로 쓴 포인트를 돌려준다. 멱등 — 이미 돌려줬으면 처음 결과 그대로.
            사용 기록이 없으면(사용 단계 실패 · 미호출) restoredAmount 0 으로 200 — 보상은 항상 성공으로 끝난다.""")
    @DeleteMapping("/{sagaId}")
    public ApiResponse<PointRestoreResponse> cancelUse(@PathVariable String sagaId) {
        return ApiResponse.success("포인트 사용을 취소했습니다.", pointService.cancelUse(sagaId));
    }
}
