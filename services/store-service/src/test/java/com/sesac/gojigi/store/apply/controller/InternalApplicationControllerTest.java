package com.sesac.gojigi.store.apply.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.Document;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.History;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SameBizRegNo;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SameStore;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SettlementAccount;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.service.ApplicationQueryService;
import com.sesac.gojigi.store.store.entity.StoreStatus;
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

    @Test
    @DisplayName("상세 조회 — 200, 계좌 · 서류(열람 주소 없음은 null) · 이력 · 같은 사업자번호")
    void get_whenApplicationExists_returns200() throws Exception {
        // given
        OffsetDateTime at = OffsetDateTime.parse("2026-10-22T14:05:11+09:00");
        given(applicationQueryService.getDetail(27L)).willReturn(new InternalApplicationDetailResponse(
                27L, "AP-2610-027", ApplicationStatus.PENDING, null, at, at, null,
                BizType.INDIVIDUAL, "곳간상회", "홍길동", "000-00-00001", "2026-서울성동-0000", "010-0000-1234",
                "seller@example.com", "서울시 성동구", "곳간상회", "gotgan-store", 1L, "<p>소개</p>",
                List.of(new RepresentativeProduct("리넨 셔츠", "셔츠", 39000)), null, null,
                new SettlementAccount("국민", "000000-00-000000", "홍길동"), false, at,
                List.of(new Document(101L, DocType.BUSINESS_REG, "biz.pdf", "application/pdf", 1234, true, at, null, null)),
                List.of(new History(null, ApplicationStatus.PENDING, ActorType.APPLICANT, null, at)),
                new SameBizRegNo(List.of(), List.of(new SameStore(5L, "곳간상점", StoreStatus.CLOSED)))));

        // when · then
        mockMvc.perform(get("/internal/applications/27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationId").value(27))
                .andExpect(jsonPath("$.data.storeId").value(nullValue()))
                .andExpect(jsonPath("$.data.settlementAccount.number").value("000000-00-000000"))
                .andExpect(jsonPath("$.data.representativeProducts[0].price").value(39000))
                .andExpect(jsonPath("$.data.documents[0].docType").value("BUSINESS_REG"))
                .andExpect(jsonPath("$.data.documents[0].url").value(nullValue()))
                .andExpect(jsonPath("$.data.history[0].actor").value("APPLICANT"))
                .andExpect(jsonPath("$.data.history[0].fromStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.sameBizRegNo.applications").isEmpty())
                .andExpect(jsonPath("$.data.sameBizRegNo.stores[0].status").value("CLOSED"));
    }

    @Test
    @DisplayName("없는 신청 — 404 APPLY_NOT_FOUND, data 는 null")
    void get_whenApplicationMissing_returns404() throws Exception {
        // given
        given(applicationQueryService.getDetail(99L)).willThrow(new ApplyException(ApplyErrorCode.APPLY_NOT_FOUND));

        // when · then
        mockMvc.perform(get("/internal/applications/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLY_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("applicationId 가 숫자가 아니면 400 INVALID_INPUT")
    void get_whenApplicationIdNotNumber_returns400() throws Exception {
        mockMvc.perform(get("/internal/applications/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
