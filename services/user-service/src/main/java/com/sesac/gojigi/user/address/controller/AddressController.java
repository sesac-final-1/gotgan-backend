package com.sesac.gojigi.user.address.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.address.dto.request.AddressRequest;
import com.sesac.gojigi.user.address.dto.response.AddressResponse;
import com.sesac.gojigi.user.address.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 배송지 — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 (/api/users/** 인증 필요) */
@Tag(name = "배송지", description = "내 배송지 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequestMapping("/api/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @Operation(summary = "배송지 등록", description = """
            인가: 로그인 회원 본인. main=true 면 기존 기본 배송지를 해제하고 기본으로, 첫 배송지는 main 과 관계없이 기본.
            연락처는 휴대폰(010-1234-5678 로 저장), 우편번호 5자리, 상세 주소는 선택. 회원당 최대 5개. 응답은 등록된 배송지(201).
            에러: 400 INVALID_INPUT(형식) · 401 UNAUTHORIZED · 404 USER_NOT_FOUND(탈퇴) · 409 ADDRESS_LIMIT_EXCEEDED(5개 초과)""")
    @PostMapping
    public ResponseEntity<ApiResponse<AddressResponse>> create(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody AddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("배송지를 등록했습니다.", addressService.create(user.userId(), request)));
    }

    @Operation(summary = "배송지 수정", description = """
            인가: 로그인 회원 본인. 내용 전체를 바꾼다(등록과 같은 본문 · 검증). main=true 면 기존 기본을 해제하고 이 배송지를 기본으로,
            main=false 로 와도 지금 기본이면 기본 유지(기본이 없는 상태를 만들지 않음). 응답은 수정된 배송지(200).
            에러: 400 INVALID_INPUT(형식) · 401 UNAUTHORIZED · 404 ADDRESS_NOT_FOUND(없는 · 삭제된 · 다른 회원의 배송지) · 404 USER_NOT_FOUND(탈퇴)""")
    @PutMapping("/{addressId}")
    public ApiResponse<AddressResponse> update(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @PathVariable Long addressId, @Valid @RequestBody AddressRequest request) {
        return ApiResponse.success("배송지를 수정했습니다.", addressService.update(user.userId(), addressId, request));
    }
}
