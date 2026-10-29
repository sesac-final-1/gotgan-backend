package com.sesac.gojigi.store.store.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.store.dto.response.PublicStoreResponse;
import com.sesac.gojigi.store.store.dto.response.PublicStoreSummaryResponse;
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
import org.springframework.web.bind.annotation.RestController;

/** 공개 스토어 (docs/api/store.md 14절, STR-12) — 게이트웨이가 GET /api/stores/** 를 로그인 없이 통과시킨다 */
@Tag(name = "공개 스토어", description = "인가: 공개 — 운영 중 스토어만")
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class PublicStoreController {

    private final StoreQueryService storeQueryService;

    @Operation(summary = "스토어 목록",
            description = """
            인가: 공개. 운영 중(ACTIVE) 스토어만 개설 최신순, 페이징 없이 전체 — 화면이 카테고리 탭으로 거른다.
            카테고리는 입점 신청 때 고른 상위 카테고리 ID(이름은 카테고리 API), 상품 미리보기는 상품 목록 API(storeId 필터).
            정지 · 폐점 스토어는 빠진다. 에러: 없음(없으면 빈 배열)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공 — 없으면 빈 배열")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<PublicStoreSummaryResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success("스토어 목록을 조회했습니다.", storeQueryService.getPublicList()));
    }

    @Operation(summary = "스토어 홈",
            description = """
            인가: 공개. 운영 중이면 홈 전체 — 목록 필드 + 판매자 정보(대표자 · 사업자등록번호는 원래 신청서, 고객 문의 연락처) · 배송 정책 ·
            출고 안내. 정지 · 폐점이면 200 으로 storeId · storeName · status 만(나머지 null) — 화면은 "운영하지 않는 스토어" 안내.
            상품은 상품 목록 API(storeId 필터). 에러: 400 INVALID_INPUT(숫자 아님) · 404 STORE_NOT_FOUND(없거나 삭제)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공 — 정지 · 폐점도 200(status 로 구분)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND")
    })
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<PublicStoreResponse>> get(
            @Parameter(description = "스토어 ID", example = "1") @PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.success("스토어를 조회했습니다.", storeQueryService.getPublic(storeId)));
    }
}
