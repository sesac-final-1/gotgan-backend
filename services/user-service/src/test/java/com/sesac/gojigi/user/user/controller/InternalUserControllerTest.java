package com.sesac.gojigi.user.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.user.user.dto.request.ChangeUserStatusRequest;
import com.sesac.gojigi.user.user.dto.response.AdminUserDetailResponse;
import com.sesac.gojigi.user.user.dto.response.AdminUserSummaryResponse;
import com.sesac.gojigi.user.user.entity.status.Grade;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.service.AdminUserService;
import com.sesac.gojigi.user.user.service.UserStatusService;
import java.time.OffsetDateTime;
import java.util.List;
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
class InternalUserControllerTest {

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private UserStatusService userStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalUserController(adminUserService, userStatusService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static final OffsetDateTime JOINED = OffsetDateTime.parse("2026-10-01T09:30:00+09:00");

    @Test
    @DisplayName("목록 — 검색어 · 상태 · 페이지를 그대로 넘기고 PageResponse 로 응답")
    void search() throws Exception {
        given(adminUserService.search("kim", UserStatus.SUSPENDED, 1, 10)).willReturn(new PageResponse<>(
                List.of(new AdminUserSummaryResponse(7L, "kim@gotgan.com", "김곳간", null, Grade.SILVER,
                        UserStatus.SUSPENDED, SignupProvider.KAKAO, 1500L, 320000L, JOINED, null)),
                1, 10, 11, 2, false));

        mockMvc.perform(get("/internal/users").param("q", "kim").param("status", "SUSPENDED")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(7))
                .andExpect(jsonPath("$.data.content[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.content[0].joinedAt").value("2026-10-01T09:30:00+09:00"))
                .andExpect(jsonPath("$.data.totalElements").value(11))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("목록 — 파라미터 생략 시 전체 · 0페이지 · 20개")
    void search_defaults() throws Exception {
        given(adminUserService.search(null, null, 0, 20)).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/internal/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("목록 — size 범위 밖 · 음수 page · 없는 status 는 서비스 호출 없이 400 INVALID_INPUT")
    void search_invalidParams() throws Exception {
        for (String[] param : new String[][] { { "size", "101" }, { "size", "0" }, { "page", "-1" }, { "status", "DORMANT" } }) {
            mockMvc.perform(get("/internal/users").param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(adminUserService);
    }

    @Test
    @DisplayName("상세 — 200 상세, 없는 회원 · 탈퇴는 404 USER_NOT_FOUND")
    void detail() throws Exception {
        given(adminUserService.getDetail(7L)).willReturn(suspendedDetail());
        given(adminUserService.getDetail(99L)).willThrow(new UserException(UserErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/internal/users/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusReason").value("허위 리뷰"))
                .andExpect(jsonPath("$.data.suspendedAt").value("2026-10-22T14:00:00+09:00"))
                .andExpect(jsonPath("$.data.suspendedUntil").value("2026-10-29T14:00:00+09:00"));
        mockMvc.perform(get("/internal/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    private static AdminUserDetailResponse suspendedDetail() {
        return new AdminUserDetailResponse(7L, "kim@gotgan.com", "김곳간", null, "010-1234-5678", Grade.NORMAL,
                UserStatus.SUSPENDED, "허위 리뷰", OffsetDateTime.parse("2026-10-22T14:00:00+09:00"),
                OffsetDateTime.parse("2026-10-29T14:00:00+09:00"), SignupProvider.LOCAL, 0L, 0L, JOINED, null);
    }

    @Test
    @DisplayName("이용정지 — 본문 · 관리자 ID 를 넘기고 바뀐 상세로 응답, 해제는 다른 메시지")
    void changeStatus() throws Exception {
        ChangeUserStatusRequest suspend = new ChangeUserStatusRequest(UserStatus.SUSPENDED, "허위 리뷰", 7);
        given(userStatusService.changeStatus(7L, suspend, 1L)).willReturn(suspendedDetail());

        mockMvc.perform(patch("/internal/users/7/status").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"허위 리뷰\",\"days\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("회원을 이용정지했습니다."))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.suspendedUntil").value("2026-10-29T14:00:00+09:00"));

        ChangeUserStatusRequest activate = new ChangeUserStatusRequest(UserStatus.ACTIVE, "소명 완료", null);
        given(userStatusService.changeStatus(7L, activate, null)).willReturn(suspendedDetail());
        mockMvc.perform(patch("/internal/users/7/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"reason\":\"소명 완료\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("회원 이용정지를 해제했습니다."));
    }

    @Test
    @DisplayName("이용정지 — 상태 없음 · days 0 · 366 · 없는 상태는 서비스 호출 없이 400 INVALID_INPUT")
    void changeStatus_invalidBody() throws Exception {
        for (String body : new String[] {
                "{\"reason\":\"사유\"}",
                "{\"status\":\"SUSPENDED\",\"reason\":\"사유\",\"days\":0}",
                "{\"status\":\"SUSPENDED\",\"reason\":\"사유\",\"days\":366}",
                "{\"status\":\"WRONG\",\"reason\":\"사유\"}" }) {
            mockMvc.perform(patch("/internal/users/7/status").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(userStatusService);
    }

    @Test
    @DisplayName("이용정지 — 사유 없음 400 · 이미 정지 409 · 없는 회원 404 는 서비스 에러 코드 그대로")
    void changeStatus_serviceErrors() throws Exception {
        given(userStatusService.changeStatus(eq(7L), any(), any()))
                .willThrow(new UserException(UserErrorCode.USER_STATUS_REASON_REQUIRED),
                        new UserException(UserErrorCode.USER_INVALID_STATUS_TRANSITION),
                        new UserException(UserErrorCode.USER_NOT_FOUND));
        String body = "{\"status\":\"SUSPENDED\",\"reason\":\" \"}";

        mockMvc.perform(patch("/internal/users/7/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_STATUS_REASON_REQUIRED"));
        mockMvc.perform(patch("/internal/users/7/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_INVALID_STATUS_TRANSITION"));
        mockMvc.perform(patch("/internal/users/7/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
