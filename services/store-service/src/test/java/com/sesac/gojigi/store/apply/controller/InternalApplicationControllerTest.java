package com.sesac.gojigi.store.apply.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.service.ApplicationQueryService;
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
class InternalApplicationControllerTest {

    @Mock
    private ApplicationQueryService applicationQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalApplicationController(applicationQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("상태 필터로 목록 조회 — 200, data 는 배열이고 시각은 +09:00")
    void list_withStatus_returns200Array() throws Exception {
        // given
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-22T14:05:11+09:00");
        given(applicationQueryService.getList(ApplicationStatus.PENDING)).willReturn(List.of(
                new InternalApplicationSummaryResponse(27L, "AP-2610-027", "곳간상회", "홍길동", BizType.INDIVIDUAL, 1L,
                        ExpectedMonthlySales.FROM_5M_TO_20M, ApplicationStatus.PENDING, submittedAt, submittedAt)));

        // when · then
        mockMvc.perform(get("/internal/applications").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applicationId").value(27))
                .andExpect(jsonPath("$.data[0].applicationNo").value("AP-2610-027"))
                .andExpect(jsonPath("$.data[0].categoryId").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].submittedAt").value("2026-10-22T14:05:11+09:00"));
    }

    @Test
    @DisplayName("status 없이 부르면 전체 — 서비스에 null 을 넘기고, 없으면 빈 배열")
    void list_withoutStatus_returnsEmptyArray() throws Exception {
        // given
        given(applicationQueryService.getList(null)).willReturn(List.of());

        // when · then
        mockMvc.perform(get("/internal/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("모르는 status 값은 400 INVALID_INPUT — 서비스를 부르지 않는다")
    void list_whenStatusUnknown_returns400() throws Exception {
        mockMvc.perform(get("/internal/applications").param("status", "DORMANT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        then(applicationQueryService).should(never()).getList(any());
    }
}
