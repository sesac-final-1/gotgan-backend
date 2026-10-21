package com.sesac.gojigi.store.store.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.service.StoreQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 간 호출 전용 — /internal/** 는 게이트웨이 라우트가 없어 외부에서 닿지 않는다 (docs/api/store.md) */
@Tag(name = "스토어 (내부)", description = "user-service · admin-service 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/stores")
@RequiredArgsConstructor
public class InternalStoreController {

    private final StoreQueryService storeQueryService;

    @Operation(summary = "스토어 상세 (상태 포함)",
            description = "인가: 내부. 판매자 탈퇴 전 폐점 확인 등. status = ACTIVE · DORMANT · SUSPENDED · CLOSED. "
                    + "없는 스토어는 404 STORE_NOT_FOUND")
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
