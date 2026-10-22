package com.sesac.gojigi.user.point.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.user.point.dto.response.MyPointsResponse;
import com.sesac.gojigi.user.point.dto.response.UserPointsResponse;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.service.PointService;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalUserPointControllerTest {

    @Mock
    private PointService pointService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalUserPointController(pointService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("회원 포인트 — 잔액 + 이력 PageResponse, page · size 를 그대로 넘긴다")
    void getUserPoints() throws Exception {
        given(pointService.getUserPoints(7L, 1, 10)).willReturn(new UserPointsResponse(700L, new PageResponse<>(
                List.of(new MyPointsResponse.Item(3L, PointType.USE, 300L, 700L, "20261022-0001",
                        OffsetDateTime.parse("2026-10-22T11:00:00+09:00"))),
                1, 10, 11, 2, false)));

        mockMvc.perform(get("/internal/users/7/points").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(700))
                .andExpect(jsonPath("$.data.history.content[0].type").value("USE"))
                .andExpect(jsonPath("$.data.history.content[0].createdAt").value("2026-10-22T11:00:00+09:00"))
                .andExpect(jsonPath("$.data.history.totalElements").value(11))
                .andExpect(jsonPath("$.data.history.hasNext").value(false));
    }

    @Test
    @DisplayName("파라미터 생략 시 0페이지 · 20개, 없는 회원 · 탈퇴는 404 USER_NOT_FOUND")
    void getUserPoints_defaultsAndNotFound() throws Exception {
        given(pointService.getUserPoints(7L, 0, 20))
                .willReturn(new UserPointsResponse(0L, new PageResponse<>(List.of(), 0, 20, 0, 0, false)));
        given(pointService.getUserPoints(99L, 0, 20)).willThrow(new UserException(UserErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/internal/users/7/points"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.size").value(20));
        mockMvc.perform(get("/internal/users/99/points"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("size 범위 밖 · 음수 page 는 서비스 호출 없이 400 INVALID_INPUT")
    void getUserPoints_invalidParams() throws Exception {
        for (String[] param : new String[][] { { "size", "101" }, { "size", "0" }, { "page", "-1" } }) {
            mockMvc.perform(get("/internal/users/7/points").param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(pointService);
    }
}
