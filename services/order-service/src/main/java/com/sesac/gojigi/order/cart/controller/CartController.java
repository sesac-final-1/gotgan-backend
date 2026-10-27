package com.sesac.gojigi.order.cart.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.cart.dto.request.CartCheckRequest;
import com.sesac.gojigi.order.cart.dto.request.CartItemAddRequest;
import com.sesac.gojigi.order.cart.dto.request.CartItemUpdateRequest;
import com.sesac.gojigi.order.cart.dto.response.CartItemsResponse;
import com.sesac.gojigi.order.cart.dto.response.CartResponse;
import com.sesac.gojigi.order.cart.service.CartQueryService;
import com.sesac.gojigi.order.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 구매자 장바구니 조회 · 담기 · 수량 · 선택 · 삭제 (ORD-01 · 02) — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 */
@Tag(name = "장바구니", description = "조회 · 담기 · 수량 · 선택 · 삭제 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CartQueryService cartQueryService;

    @Operation(summary = "장바구니 담기", description = """
            옵션 단위로 여러 개를 한 번에 담는다. 이미 담긴 옵션은 수량을 더하고, 요청 안의 같은 옵션도 합친다.
            product 에서 옵션이 있고 구매 가능한지 확인한다(가격 · 품절 표시는 조회에서 다시).
            응답은 담은 뒤 내 장바구니 전체(옵션 · 수량)와 줄 수(cartCount, 헤더 배지).
            에러: 400 INVALID_INPUT / 409 CART_PRODUCT_NOT_PURCHASABLE(없는 옵션 · 판매중지 등, 하나라도 있으면 전부 거절) ·
            CART_QUANTITY_EXCEEDED(옵션당 999개 초과) / 502 EXTERNAL_API_ERROR(product 장애)""")
    @PostMapping("/api/carts/items")
    public ApiResponse<CartItemsResponse> add(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                              @Valid @RequestBody CartItemAddRequest request) {
        return ApiResponse.success("장바구니에 담았습니다.", cartService.add(user.userId(), request));
    }

    @Operation(summary = "장바구니 조회", description = """
            ORD-02: 스토어별 묶음 · 스토어별 배송비 1회 · 무료배송까지 남은 금액(amountToFree). 가격 · 품절은 product 에서 다시 확인.
            금액 · 배송비는 선택(checked)되고 구매 가능한 줄만 — 주문서와 같다. product 에 없는 옵션은 unavailable 로.
            Redis 캐시 → 미스 · 장애면 MySQL 원본(그때 캐시를 다시 채움). 에러: 502 EXTERNAL_API_ERROR(product 장애)""")
    @GetMapping("/api/carts")
    public ApiResponse<CartResponse> get(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("장바구니를 조회했습니다.", cartQueryService.get(user.userId()));
    }

    @Operation(summary = "장바구니 수량 · 선택 변경", description = """
            보낸 것만 바꾼다 — quantity(1~999) · checked. MySQL 에 먼저 쓰고 커밋 뒤 Redis 갱신.
            에러: 400 INVALID_INPUT(둘 다 없음 · 범위 밖) / 404 CART_ITEM_NOT_FOUND(장바구니에 없는 옵션)""")
    @PatchMapping("/api/carts/items/{productOptionId}")
    public ApiResponse<CartItemsResponse> update(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                 @PathVariable Long productOptionId,
                                                 @Valid @RequestBody CartItemUpdateRequest request) {
        return ApiResponse.success("장바구니를 수정했습니다.", cartService.update(user.userId(), productOptionId, request));
    }

    @Operation(summary = "장바구니 선택 한꺼번에", description = """
            전체 선택 · 해제(productOptionIds 없음) 또는 고른 옵션들. 장바구니에 없는 옵션은 건너뛴다.""")
    @PatchMapping("/api/carts/items/checked")
    public ApiResponse<CartItemsResponse> check(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                @Valid @RequestBody CartCheckRequest request) {
        return ApiResponse.success("장바구니를 수정했습니다.",
                cartService.check(user.userId(), request.productOptionIds(), request.checked()));
    }

    @Operation(summary = "장바구니 삭제 (단건 · 선택)", description = """
            옵션 ID 를 하나 또는 여러 개 받아 지운다(?productOptionIds=11,12). 장바구니에 없는 옵션은 건너뛴다.
            응답은 지운 뒤 내 장바구니 전체. 에러: 400 INVALID_INPUT(옵션 ID 없음 · 100개 초과)""")
    @DeleteMapping("/api/carts/items")
    public ApiResponse<CartItemsResponse> remove(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                 @RequestParam @NotEmpty @Size(max = 100) List<Long> productOptionIds) {
        return ApiResponse.success("장바구니에서 삭제했습니다.", cartService.remove(user.userId(), productOptionIds));
    }
}
