package com.sesac.gojigi.store.store.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreSummaryResponse;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.service.StoreQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 간 호출 전용 — /internal/** 는 게이트웨이 라우트가 없어 외부에서 닿지 않는다 (docs/api/store.md) */
@Tag(name = "스토어 (내부)", description = "user · admin · chat · order-service 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/stores")
@RequiredArgsConstructor
public class InternalStoreController {

    private final StoreQueryService storeQueryService;

    @Operation(summary = "스토어 목록 (관리자)",
            description = "인가: 내부. admin 스토어 관리 — 페이징 없이 전체 배열, 소프트 삭제 제외, 개설 최신순. "
                    + "status(ACTIVE · SUSPENDED · CLOSED)를 생략하면 전체, 모르는 값이면 400 INVALID_INPUT. "
                    + "대표자 · 사업자번호는 원래 신청서 값")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공 (없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT — status 값 오류")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<InternalStoreSummaryResponse>>> list(
            @Parameter(description = "스토어 상태 필터 (ACTIVE · SUSPENDED · CLOSED)", example = "SUSPENDED")
            @RequestParam(required = false) StoreStatus status) {
        return ResponseEntity.ok(ApiResponse.success("스토어 목록을 조회했습니다.", storeQueryService.getAdminList(status)));
    }

    @Operation(summary = "스토어 상세 (상태 · 배송 정책 포함)",
            description = "인가: 내부. 판매자 탈퇴 전 폐점 확인 · 주문 배송비 계산 등. status = ACTIVE · SUSPENDED · CLOSED. "
                    + "shippingFee 기본 배송비(원), freeShippingThreshold 무료배송 기준(원, null = 무료배송 없음), "
                    + "courier 기본 택배사(null 가능). 없는 스토어는 404 STORE_NOT_FOUND")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND")
    })
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<InternalStoreResponse>> get(
            @Parameter(description = "스토어 ID", example = "1") @PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.success("스토어를 조회했습니다.", storeQueryService.getInternal(storeId)));
    }
}
