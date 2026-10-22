package com.sesac.gojigi.user.point.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.user.point.dto.response.MyPointsResponse;
import com.sesac.gojigi.user.point.dto.response.PointRestoreResponse;
import com.sesac.gojigi.user.point.dto.response.PointUseResponse;
import com.sesac.gojigi.user.point.dto.response.UserPointsResponse;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.exception.PointErrorCode;
import com.sesac.gojigi.user.point.exception.PointException;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 내 포인트(외부) · 사용 · 사용 취소 · 회원 포인트(내부) 컨트롤러 */
@ExtendWith(MockitoExtension.class)
class PointControllerTest {

    @Mock
    private PointService pointService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PointController(pointService), new InternalPointController(pointService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static final String USE_BODY = "{\"sagaId\":\"saga-1\",\"userId\":4,\"orderNo\":\"20261022-0001\",\"amount\":300}";

    @Test
    @DisplayName("내 포인트 — 잔액 · 이력(type · 양수 amount · 오프셋 포함 시각)")
    void getMyPoints() throws Exception {
        given(pointService.getMyPoints(4L)).willReturn(new MyPointsResponse(700L, List.of(new MyPointsResponse.Item(
                1L, PointType.USE, 300L, 700L, "20261022-0001", OffsetDateTime.parse("2026-10-22T11:00:00+09:00")))));

        mockMvc.perform(get("/api/users/me/points").header("X-User-Id", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(700))
                .andExpect(jsonPath("$.data.history[0].type").value("USE"))
                .andExpect(jsonPath("$.data.history[0].amount").value(300))
                .andExpect(jsonPath("$.data.history[0].createdAt").value("2026-10-22T11:00:00+09:00"));
    }

    @Test
    @DisplayName("X-User-Id 없으면 401")
    void getMyPoints_withoutUser() throws Exception {
        mockMvc.perform(get("/api/users/me/points")).andExpect(status().isUnauthorized());
        verifyNoInteractions(pointService);
    }

    @Test
    @DisplayName("사용 — 처음 201, 같은 sagaId 재요청 200")
    void use_createdThenOk() throws Exception {
        PointUseResponse response = new PointUseResponse("saga-1", 4L, "20261022-0001", 300L, 700L);
        given(pointService.use(any())).willReturn(new PointService.UseResult(response, true),
                new PointService.UseResult(response, false));

        mockMvc.perform(post("/internal/points/use").contentType(MediaType.APPLICATION_JSON).content(USE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.balanceAfter").value(700));
        mockMvc.perform(post("/internal/points/use").contentType(MediaType.APPLICATION_JSON).content(USE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("사용 — 잔액 부족 409 POINT_INSUFFICIENT, amount 0 · sagaId 없음은 400")
    void use_errors() throws Exception {
        given(pointService.use(any())).willThrow(new PointException(PointErrorCode.POINT_INSUFFICIENT));
        mockMvc.perform(post("/internal/points/use").contentType(MediaType.APPLICATION_JSON).content(USE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POINT_INSUFFICIENT"));

        mockMvc.perform(post("/internal/points/use").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sagaId\":\"s\",\"userId\":4,\"orderNo\":\"o\",\"amount\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/points/use").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":4,\"orderNo\":\"o\",\"amount\":100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("사용 취소 — 돌려준 금액 · 잔액, 사용 기록이 없어도 200 (restoredAmount 0)")
    void cancelUse() throws Exception {
        given(pointService.cancelUse("saga-1")).willReturn(new PointRestoreResponse("saga-1", 300L, 1000L));
        given(pointService.cancelUse("saga-x")).willReturn(PointRestoreResponse.nothingToRestore("saga-x"));

        mockMvc.perform(delete("/internal/points/use/saga-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restoredAmount").value(300))
                .andExpect(jsonPath("$.data.balanceAfter").value(1000));
        mockMvc.perform(delete("/internal/points/use/saga-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restoredAmount").value(0));
    }

    @Test
    @DisplayName("회원 포인트(관리자) — 잔액 + 이력 PageResponse, page · size 를 그대로 넘긴다")
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
    @DisplayName("회원 포인트(관리자) — 파라미터 생략 시 0페이지 · 20개, 없는 회원 · 탈퇴는 404 USER_NOT_FOUND")
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
    @DisplayName("회원 포인트(관리자) — size 범위 밖 · 음수 page 는 서비스 호출 없이 400 INVALID_INPUT")
    void getUserPoints_invalidParams() throws Exception {
        for (String[] param : new String[][] { { "size", "101" }, { "size", "0" }, { "page", "-1" } }) {
            mockMvc.perform(get("/internal/users/7/points").param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(pointService);
    }
}
