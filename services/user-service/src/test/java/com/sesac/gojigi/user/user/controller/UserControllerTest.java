package com.sesac.gojigi.user.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.user.user.dto.request.ChangePasswordRequest;
import com.sesac.gojigi.user.user.dto.request.UpdateMyInfoRequest;
import com.sesac.gojigi.user.user.dto.response.MyInfoResponse;
import com.sesac.gojigi.user.user.entity.status.Grade;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.service.UserService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("탈퇴 — 게이트웨이가 넣어 준 X-User-Id 의 회원을 탈퇴시킨다")
    void withdraw_withUserHeader_withdrawsThatUser() throws Exception {
        mockMvc.perform(delete("/api/users/me").header("X-User-Id", "7").header("X-User-Roles", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."));
        verify(userService).withdraw(7L, null);
    }

    @Test
    @DisplayName("X-User-Id 가 없으면(게이트웨이를 거치지 않음) 401 UNAUTHORIZED")
    void withdraw_withoutUserHeader_returns401() throws Exception {
        mockMvc.perform(delete("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("이미 탈퇴한 회원은 404 USER_NOT_FOUND")
    void withdraw_alreadyWithdrawn_returns404() throws Exception {
        willThrow(new UserException(UserErrorCode.USER_NOT_FOUND)).given(userService).withdraw(7L, null);

        mockMvc.perform(delete("/api/users/me").header("X-User-Id", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("판매자 — X-Store-Id 를 함께 넘기고, 스토어 미폐점이면 409 USER_SELLER_STORE_NOT_CLOSED")
    void withdraw_sellerWithOpenStore_returns409() throws Exception {
        willThrow(new UserException(UserErrorCode.USER_SELLER_STORE_NOT_CLOSED)).given(userService).withdraw(2L, 1L);

        mockMvc.perform(delete("/api/users/me").header("X-User-Id", "2").header("X-User-Roles", "BUYER,SELLER")
                        .header("X-Store-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_SELLER_STORE_NOT_CLOSED"));
    }

    @Test
    @DisplayName("내 정보 조회 — X-User-Id 회원의 정보, 가입일은 오프셋 포함 ISO-8601")
    void getMyInfo_returnsProfile() throws Exception {
        given(userService.getMyInfo(4L)).willReturn(new MyInfoResponse(4L, "buyer1@gotgan.com", "구매자1", null,
                "010-0000-0004", Grade.NORMAL, SignupProvider.LOCAL, 1500L, 82000L,
                OffsetDateTime.parse("2026-10-01T09:30:00+09:00")));

        mockMvc.perform(get("/api/users/me").header("X-User-Id", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(4))
                .andExpect(jsonPath("$.data.signupProvider").value("LOCAL"))
                .andExpect(jsonPath("$.data.point").value(1500))
                .andExpect(jsonPath("$.data.totalSpent").value(82000))
                .andExpect(jsonPath("$.data.joinedAt").value("2026-10-01T09:30:00+09:00"));
    }

    @Test
    @DisplayName("내 정보 수정 — 본문을 그대로 서비스로, 휴대폰 형식 · 이름 공백은 400 INVALID_INPUT")
    void updateMyInfo_validation() throws Exception {
        given(userService.updateMyInfo(eq(4L), any())).willReturn(new MyInfoResponse(4L, "buyer1@gotgan.com", "김곳간",
                null, "010-1234-5678", Grade.NORMAL, SignupProvider.LOCAL, 0L, 0L, null));

        mockMvc.perform(patch("/api/users/me").header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"김곳간\",\"phone\":\"010-1234-5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("김곳간"));
        verify(userService).updateMyInfo(4L, new UpdateMyInfoRequest("김곳간", null, "010-1234-5678"));

        mockMvc.perform(patch("/api/users/me").header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"02-123-4567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(patch("/api/users/me").header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("비밀번호 변경 — 성공 200, 현재 비밀번호 불일치는 401 이 아니라 400 USER_PASSWORD_MISMATCH")
    void changePassword() throws Exception {
        mockMvc.perform(patch("/api/users/me/password").header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Old!pass1\",\"newPassword\":\"New!pass2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("비밀번호를 변경했습니다."));
        verify(userService).changePassword(4L, new ChangePasswordRequest("Old!pass1", "New!pass2"));

        willThrow(new UserException(UserErrorCode.USER_PASSWORD_MISMATCH)).given(userService).changePassword(eq(4L), any());
        mockMvc.perform(patch("/api/users/me/password").header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"New!pass2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_PASSWORD_MISMATCH"));
    }

    @Test
    @DisplayName("새 비밀번호 형식(8자 · 영문 · 특수문자) 위반은 서비스 호출 없이 400 INVALID_INPUT")
    void changePassword_weakNewPassword() throws Exception {
        mockMvc.perform(patch("/api/users/me/password").header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Old!pass1\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(userService);
    }
}
