package com.sesac.gojigi.user.user.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 본인 API — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 (/api/users/** 인증 필요).
 */
@Tag(name = "회원", description = "내 회원 정보 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "회원 탈퇴", description = """
            인가: 로그인 회원 본인. 회원 행 소프트 삭제 + Keycloak 계정 삭제 (로그인 · Refresh 불가).
            같은 이메일로 재가입할 수 없음. 판매자는 스토어를 폐점한 뒤에만 탈퇴 가능. 프론트는 성공 후 Access Token 을 버린다.
            에러: 401 UNAUTHORIZED(게이트웨이 헤더 없음) · 404 USER_NOT_FOUND(이미 탈퇴) · 409 USER_SELLER_STORE_NOT_CLOSED""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "탈퇴 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED — X-User-Id 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND — 없는 회원 · 이미 탈퇴"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "USER_SELLER_STORE_NOT_CLOSED — 판매자 스토어 미폐점")
    })
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        userService.withdraw(user.userId(), user.storeId());
        return ApiResponse.success("회원 탈퇴가 완료되었습니다.");
    }
}
