package com.sesac.gojigi.store.store.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.store.dto.request.SellerStoreUpdateRequest;
import com.sesac.gojigi.store.store.dto.request.StoreImagePresignRequest;
import com.sesac.gojigi.store.store.dto.response.SellerStoreResponse;
import com.sesac.gojigi.store.store.dto.response.StoreCloseResponse;
import com.sesac.gojigi.store.store.dto.response.StoreImagePresignResponse;
import com.sesac.gojigi.store.store.service.SellerStoreService;
import com.sesac.gojigi.store.store.service.StoreQueryService;
import com.sesac.gojigi.store.store.service.StoreStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 내 스토어 (docs/api/store.md 12 · 13절) — 역할 확인은 게이트웨이(/api/seller/** = SELLER), store 는 X-Store-Id 스토어만 본다 */
@Tag(name = "판매자 스토어", description = "인가: SELLER — X-Store-Id 스토어만")
@RestController
@RequestMapping("/api/seller/store")
@RequiredArgsConstructor
public class SellerStoreController {

    private final StoreQueryService storeQueryService;
    private final SellerStoreService sellerStoreService;
    private final StoreStatusService storeStatusService;

    @Operation(summary = "내 스토어 조회",
            description = """
            인가: SELLER (스토어 = X-Store-Id). 스토어 설정 화면 — 소개 · 대표 이미지(imageKey · imageUrl) · 표시 색 · 고객 문의 연락처 ·
            배송 정책(기본 배송비 · 무료배송 기준 · 택배사 · 출고 안내) · 등급 · 대표자 · 사업자번호(원래 신청서) · 개설 시각.
            정지 · 폐점 스토어도 조회된다. 에러: 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 없음) · 404 STORE_NOT_FOUND""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — X-Store-Id 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<SellerStoreResponse>> getMyStore(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ResponseEntity.ok(ApiResponse.success("내 스토어를 조회했습니다.", storeQueryService.getSellerStore(storeId(user))));
    }

    @Operation(summary = "내 스토어 설정 수정",
            description = """
            인가: SELLER (스토어 = X-Store-Id). 설정 전체를 보낸다(PUT) — 소개(5000자, 서버 정화) · 대표 이미지 키(null · 지금 키 · 내
            stores/{storeId}/ 키만) · 표시 색 #RRGGBB · 고객 문의 연락처 · 기본 배송비(0~100,000원) · 무료배송 기준(null = 없음) · 택배사 ·
            출고 안내. 스토어명 · 주소는 바꾸지 않는다. 이미 들어온 주문의 배송비는 그대로(주문 시점 스냅샷). 정지 중에도 수정 가능.
            응답은 내 스토어 조회와 같은 모양. 에러: 400 INVALID_INPUT(형식 · 이미지 키) · 401 · 403 FORBIDDEN · 404 STORE_NOT_FOUND ·
            409 STORE_CLOSED(폐점 스토어)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 — 바뀐 설정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT — 형식 · 이미지 키"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — X-Store-Id 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "STORE_CLOSED — 폐점 스토어")
    })
    @PutMapping
    public ResponseEntity<ApiResponse<SellerStoreResponse>> updateMyStore(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody SellerStoreUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("스토어 설정을 저장했습니다.", sellerStoreService.update(storeId(user), request)));
    }

    @Operation(summary = "대표 이미지 업로드 주소 발급",
            description = """
            인가: SELLER (스토어 = X-Store-Id). images 버킷 presigned PUT 주소(10분) — 키 stores/{storeId}/{UUID}.{jpg|png|webp}.
            브라우저가 uploadUrl 로 PUT(Content-Type · 크기를 요청과 같게 — 서명에 포함돼 다르면 S3 403) → 받은 key 를 설정 수정의 imageKey 로.
            JPG · PNG · WEBP, 10MB 이하. 에러: 400 INVALID_INPUT(형식 · 크기) · 401 · 403 FORBIDDEN · 404 STORE_NOT_FOUND ·
            409 STORE_CLOSED · 502 EXTERNAL_API_ERROR(자격 증명)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT — 형식 · 크기"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — X-Store-Id 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "STORE_CLOSED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR")
    })
    @PostMapping("/image/presign")
    public ResponseEntity<ApiResponse<StoreImagePresignResponse>> presignImage(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody StoreImagePresignRequest request) {
        return ResponseEntity.ok(ApiResponse.success("업로드 주소를 발급했습니다.", sellerStoreService.presignImage(storeId(user), request)));
    }

    @Operation(summary = "스토어 폐점",
            description = """
            인가: SELLER (스토어 = X-Store-Id). 본문 없음. 되돌릴 수 없고 재입점할 수 없다(1인 1스토어). 정지 중이어도 폐점할 수 있다.
            order 진행 중 주문(inProgressCount) 1건 이상이면 409 STORE_ORDERS_IN_PROGRESS, settlement 미정산(집계 전 · 지급 예정 · 보류)이 있으면
            409 STORE_SETTLEMENT_OUTSTANDING — 메시지에 건수 · 금액. 두 서비스 장애 · 타임아웃이면 502 EXTERNAL_API_ERROR(확인할 수 없으면 거절).
            이미 폐점이면 그대로 200(멱등). 폐점하면 StoreStatusChanged{CLOSED} → 상품 숨김(product) · 지급 보류(settlement), 그 뒤 회원 탈퇴 가능.
            에러: 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 없음) · 404 STORE_NOT_FOUND""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "폐점 · 이미 폐점 — closedAt"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — X-Store-Id 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "STORE_ORDERS_IN_PROGRESS · STORE_SETTLEMENT_OUTSTANDING"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR — order · settlement 확인 실패")
    })
    @PatchMapping("/close")
    public ResponseEntity<ApiResponse<StoreCloseResponse>> close(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ResponseEntity.ok(ApiResponse.success("스토어를 폐점했습니다.", storeStatusService.closeBySeller(storeId(user))));
    }

    /** 스토어가 없는 판매자(X-Store-Id 없음)는 403 — product 판매자 API 와 같음 */
    private static Long storeId(AuthUser user) {
        if (user.storeId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return user.storeId();
    }
}
