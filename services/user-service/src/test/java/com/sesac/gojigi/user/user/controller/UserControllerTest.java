package com.sesac.gojigi.user.user.controller;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.user.common.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.user.common.exception.GlobalExceptionHandler;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
}
