package com.sesac.gojigi.user.user.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.user.dto.request.ChangePasswordRequest;
import com.sesac.gojigi.user.user.dto.request.UpdateMyInfoRequest;
import com.sesac.gojigi.user.user.dto.response.MyInfoResponse;
import com.sesac.gojigi.user.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "내 정보 조회", description = """
            인가: 로그인 회원 본인. signupProvider 가 LOCAL 이 아니면(소셜 가입) 비밀번호 변경 불가 · phone 이 비어 있을 수 있음.
            point = 포인트 잔액, totalSpent = 누적 구매확정 금액(등급 기준).
            에러: 401 UNAUTHORIZED(게이트웨이 헤더 없음) · 404 USER_NOT_FOUND(탈퇴)""")
    @GetMapping("/me")
    public ApiResponse<MyInfoResponse> getMyInfo(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("내 정보를 조회했습니다.", userService.getMyInfo(user.userId()));
    }

    @Operation(summary = "내 정보 수정", description = """
            인가: 로그인 회원 본인. 보낸 항목만 바꾼다(생략 = 그대로). 닉네임은 빈 문자열이면 삭제. 이메일은 바꿀 수 없음.
            응답은 수정 후 내 정보. 에러: 400 INVALID_INPUT(이름 빈 값 · 휴대폰 형식) · 404 USER_NOT_FOUND""")
    @PatchMapping("/me")
    public ApiResponse<MyInfoResponse> updateMyInfo(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody UpdateMyInfoRequest request) {
        return ApiResponse.success("내 정보를 수정했습니다.", userService.updateMyInfo(user.userId(), request));
    }

    @Operation(summary = "비밀번호 변경", description = """
            인가: 로그인 회원 본인. 현재 비밀번호를 확인한 뒤 새 비밀번호로 바꾼다. 로그인 상태(토큰 · 세션)는 그대로.
            에러: 400 INVALID_INPUT(형식) · 400 USER_PASSWORD_MISMATCH(현재 비밀번호 불일치 · 임시 잠금)
            · 400 AUTH_PASSWORD_POLICY_VIOLATION · 403 AUTH_ACCOUNT_DISABLED · 404 USER_NOT_FOUND
            · 409 USER_PASSWORD_CHANGE_UNSUPPORTED(소셜 가입 회원) · 502 EXTERNAL_API_ERROR""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT · USER_PASSWORD_MISMATCH · AUTH_PASSWORD_POLICY_VIOLATION"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "USER_PASSWORD_CHANGE_UNSUPPORTED — 소셜 가입 회원"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR — Keycloak 연결 실패 · 서킷 OPEN")
    })
    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(user.userId(), request);
        return ApiResponse.success("비밀번호를 변경했습니다.");
    }

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
