package com.sesac.gojigi.user.address.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.address.dto.request.CreateAddressRequest;
import com.sesac.gojigi.user.address.dto.response.AddressResponse;
import com.sesac.gojigi.user.address.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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
            @Valid @RequestBody CreateAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("배송지를 등록했습니다.", addressService.create(user.userId(), request)));
    }
}
