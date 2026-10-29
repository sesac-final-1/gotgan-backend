package com.sesac.gojigi.review.inquiry.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryAnswerRequest;
import com.sesac.gojigi.review.inquiry.dto.request.SellerInquiryTab;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryAnswerResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquirySummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import com.sesac.gojigi.review.inquiry.service.InquiryService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SellerInquiryControllerTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-29T10:00:00+09:00");

    @Mock
    private InquiryService inquiryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerInquiryController(inquiryService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("문의 목록 — X-Store-Id 스토어 · tab 그대로")
    void list_passesStoreAndTab() throws Exception {
        given(inquiryService.listForSeller(7L, SellerInquiryTab.UNANSWERED, 0, 10)).willReturn(new PageResponse<>(List.of(
                new SellerInquiryResponse(12L, 101L, "린넨 오버핏 셔츠", "곳간러버", "배송지 변경 가능할까요?", true, null, null, AT)),
                0, 10, 1, 1, false));

        perform(get("/api/seller/inquiries?tab=UNANSWERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].content").value("배송지 변경 가능할까요?"))
                .andExpect(jsonPath("$.data.content[0].secret").value(true))
                .andExpect(jsonPath("$.data.content[0].writerDisplayName").value("곳간러버"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/seller/inquiries?tab=NEW", "/api/seller/inquiries?page=-1", "/api/seller/inquiries?size=51" })
    @DisplayName("모르는 tab · page · size 범위 밖 — 400 INVALID_INPUT")
    void list_invalidParam_returns400(String url) throws Exception {
        perform(get(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(inquiryService);
    }

    @Test
    @DisplayName("탭 숫자 — 200")
    void summarize_returnsCounts() throws Exception {
        given(inquiryService.summarizeForSeller(7L)).willReturn(new SellerInquirySummaryResponse(3, 12, 15));

        perform(get("/api/seller/inquiries/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unanswered").value(3))
                .andExpect(jsonPath("$.data.answered").value(12))
                .andExpect(jsonPath("$.data.all").value(15));
    }

    @Test
    @DisplayName("스토어 정보 없음 — 서비스의 403 그대로")
    void summarize_withoutStore_returns403() throws Exception {
        given(inquiryService.summarizeForSeller(null)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다."));

        mockMvc.perform(get("/api/seller/inquiries/summary").header("X-User-Id", "9").header("X-User-Roles", "SELLER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("답변 — 200, 저장된 답변 · 시각")
    void answer_returns200() throws Exception {
        given(inquiryService.answer(7L, 12L, new InquiryAnswerRequest("M 추천드립니다!")))
                .willReturn(new InquiryAnswerResponse(12L, "M 추천드립니다!", AT));

        perform(put("/api/seller/inquiries/12/answer").contentType(MediaType.APPLICATION_JSON).content("{ \"content\": \"M 추천드립니다!\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.inquiryId").value(12))
                .andExpect(jsonPath("$.data.answerContent").value("M 추천드립니다!"))
                .andExpect(jsonPath("$.data.answeredAt").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{ \"content\": \"   \" }" })
    @DisplayName("답변 누락 · 공백 — 400 INVALID_INPUT")
    void answer_blank_returns400(String body) throws Exception {
        perform(put("/api/seller/inquiries/12/answer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(inquiryService);
    }

    @Test
    @DisplayName("답변 1,001자 — 400 INVALID_INPUT")
    void answer_tooLong_returns400() throws Exception {
        perform(put("/api/seller/inquiries/12/answer").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"content\": \"" + "가".repeat(1001) + "\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(inquiryService);
    }

    @Test
    @DisplayName("다른 스토어 · 없는 문의 답변 — 404 INQUIRY_NOT_FOUND")
    void answer_notFound_returns404() throws Exception {
        given(inquiryService.answer(eq(7L), eq(99L), any(InquiryAnswerRequest.class)))
                .willThrow(new InquiryException(InquiryErrorCode.INQUIRY_NOT_FOUND));

        perform(put("/api/seller/inquiries/99/answer").contentType(MediaType.APPLICATION_JSON).content("{ \"content\": \"네\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("X-User-Id 없음 — 401 UNAUTHORIZED")
    void withoutUserHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/seller/inquiries"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inquiryService);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-User-Id", "9").header("X-User-Roles", "SELLER").header("X-Store-Id", "7"));
    }
}
