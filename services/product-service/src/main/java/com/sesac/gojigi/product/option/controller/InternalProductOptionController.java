package com.sesac.gojigi.product.option.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.product.option.dto.response.ProductOptionResponse;
import com.sesac.gojigi.product.option.service.ProductOptionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상품 옵션 내부 API (product.md 4절) — order-service 가 클러스터 안에서 직접 호출 (게이트웨이 라우트 없음) */
@Tag(name = "상품 옵션 (내부)", description = "order-service 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/product-options")
@RequiredArgsConstructor
public class InternalProductOptionController {

    private final ProductOptionQueryService productOptionQueryService;

    @Operation(summary = "가격 · 구매 가능 조회", description = """
            장바구니 표시 · 주문서 · 주문 Saga 1단계(상품주문 스냅샷). DB 조회만 하므로 재시도 가능(GET).
            요청 순서대로(중복은 한 번), 없는 ID 는 목록에서 빠짐(order 가 구매 불가로 처리). 삭제된 상품 · 옵션은 purchasable=false 로 포함.
            purchasable = 판매중 · 하위/상위 카테고리 사용 중 · 스토어 운영 중 · 상품/옵션 삭제 안 됨. 품절(stock 0)은 따로 — 예약에서 거절.
            에러: 400 INVALID_INPUT(ids 없음 · 비었음 · 100개 초과 · 숫자 아님)""")
    @GetMapping
    public ApiResponse<List<ProductOptionResponse>> findOptions(
            @Parameter(description = "옵션 ID 1~100개 — ids=11,25 또는 ids=11&ids=25", example = "11,25") @RequestParam List<Long> ids) {
        return ApiResponse.success("옵션을 조회했습니다.", productOptionQueryService.findOptions(ids));
    }
}
