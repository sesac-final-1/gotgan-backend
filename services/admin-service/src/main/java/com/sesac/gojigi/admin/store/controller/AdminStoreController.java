package com.sesac.gojigi.admin.store.controller;

import com.sesac.gojigi.admin.store.client.StoreClientAdapter;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 관리자 스토어 관리 (STR-13 · 14) — store /internal/stores (docs/api/store.md 1 · 8 · 9절) 응답 · 오류 그대로 */
@Tag(name = "관리자 스토어", description = "store 내부 API 를 그대로 전달 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

    private final StoreClientAdapter storeClientAdapter;

    @Operation(summary = "스토어 목록", description = "status(ACTIVE/SUSPENDED/CLOSED, 생략 = 전체) → 배열(페이징 없음, 개설 최신순)")
    @GetMapping
    public ApiResponse<JsonNode> list(@RequestParam(required = false) String status) {
        return ApiResponse.success("스토어 목록을 조회했습니다.", storeClientAdapter.stores(status));
    }

    @Operation(summary = "스토어 상세", description = "store 1절 응답 (4주차에 관리자 필드 · 상태 이력 추가). 404 STORE_NOT_FOUND")
    @GetMapping("/{storeId}")
    public ApiResponse<JsonNode> detail(@PathVariable Long storeId) {
        return ApiResponse.success("스토어 상세를 조회했습니다.", storeClientAdapter.store(storeId));
    }

    @Operation(summary = "스토어 정지 · 해제", description = "{status: SUSPENDED/ACTIVE, reason(필수)} → 스토어 상세. 같은 상태 200(멱등) · 폐점 409 STORE_INVALID_STATUS_TRANSITION")
    @PatchMapping("/{storeId}/status")
    public ApiResponse<JsonNode> changeStatus(@PathVariable Long storeId, @RequestBody JsonNode body) {
        return ApiResponse.success("스토어 상태를 변경했습니다.", storeClientAdapter.changeStoreStatus(storeId, body));
    }
}
