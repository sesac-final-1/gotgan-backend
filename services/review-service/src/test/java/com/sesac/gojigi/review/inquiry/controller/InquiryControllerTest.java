package com.sesac.gojigi.review.inquiry.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryCreateResponse;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import com.sesac.gojigi.review.inquiry.service.InquiryService;
import java.time.OffsetDateTime;
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
class InquiryControllerTest {

    private static final String VALID_BODY = """
            { "productId": 101, "content": "M이랑 L 중 어떤 걸 추천하시나요?", "secret": true }
            """;

    @Mock
    private InquiryService inquiryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InquiryController(inquiryService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("문의 등록 — 201, 봉투 data 에 inquiryId, 비밀글 그대로 서비스에")
    void write_validRequest_returns201() throws Exception {
        // given
        given(inquiryService.write(eq(4L), any(InquiryCreateRequest.class))).willReturn(new InquiryCreateResponse(12L));

        // when · then
        perform(post("/api/inquiries"), VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.inquiryId").value(12));
        verify(inquiryService).write(4L, new InquiryCreateRequest(101L, "M이랑 L 중 어떤 걸 추천하시나요?", true));
    }

    @Test
    @DisplayName("비밀글을 생략하면 공개글")
    void write_withoutSecret_public() throws Exception {
        given(inquiryService.write(eq(4L), any(InquiryCreateRequest.class))).willReturn(new InquiryCreateResponse(13L));

        perform(post("/api/inquiries"), """
                { "productId": 101, "content": "세탁기 사용 가능한가요?" }
                """).andExpect(status().isCreated());
        verify(inquiryService).write(4L, new InquiryCreateRequest(101L, "세탁기 사용 가능한가요?", false));
    }

    @Test
    @DisplayName("X-User-Id 없음 — 401 UNAUTHORIZED, 서비스를 부르지 않는다")
    void write_withoutUserHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/inquiries").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(inquiryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ \"content\": \"재입고 되나요?\" }",
            "{ \"productId\": 101, \"content\": \"   \" }",
            "{ \"productId\": 101 }"
    })
    @DisplayName("상품 누락 · 내용 공백 · 내용 누락 — 400 INVALID_INPUT")
    void write_invalidField_returns400(String body) throws Exception {
        assertInvalidInput(post("/api/inquiries"), body);
    }

    @Test
    @DisplayName("내용 1,001자 — 400 INVALID_INPUT (등록 · 수정 모두)")
    void tooLongContent_returns400() throws Exception {
        String content = "가".repeat(1001);
        assertInvalidInput(post("/api/inquiries"), "{ \"productId\": 101, \"content\": \"" + content + "\" }");
        assertInvalidInput(put("/api/inquiries/12"), "{ \"content\": \"" + content + "\" }");
    }

    @Test
    @DisplayName("없는 상품 — 404 INQUIRY_PRODUCT_NOT_FOUND 코드 그대로")
    void write_productNotFound_returns404() throws Exception {
        given(inquiryService.write(eq(4L), any(InquiryCreateRequest.class)))
                .willThrow(new InquiryException(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND));

        perform(post("/api/inquiries"), VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("내 문의 수정 — 200, 수정된 값")
    void edit_validRequest_returns200() throws Exception {
        // given
        given(inquiryService.edit(4L, 12L, new InquiryUpdateRequest("어깨가 넓은 편인데 L 이 나을까요?", true)))
                .willReturn(new InquiryResponse(12L, 101L, "린넨 오버핏 셔츠", null, "어깨가 넓은 편인데 L 이 나을까요?", true,
                        null, null, OffsetDateTime.parse("2026-10-29T10:00:00+09:00")));

        // when · then
        perform(put("/api/inquiries/12"), """
                { "content": "어깨가 넓은 편인데 L 이 나을까요?", "secret": true }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inquiryId").value(12))
                .andExpect(jsonPath("$.data.content").value("어깨가 넓은 편인데 L 이 나을까요?"))
                .andExpect(jsonPath("$.data.secret").value(true))
                .andExpect(jsonPath("$.data.productName").value("린넨 오버핏 셔츠"));
    }

    @Test
    @DisplayName("답변 완료 문의 수정 — 409 INQUIRY_ALREADY_ANSWERED")
    void edit_answered_returns409() throws Exception {
        given(inquiryService.edit(eq(4L), eq(12L), any(InquiryUpdateRequest.class)))
                .willThrow(new InquiryException(InquiryErrorCode.INQUIRY_ALREADY_ANSWERED));

        perform(put("/api/inquiries/12"), "{ \"content\": \"바꿀래요\" }")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_ALREADY_ANSWERED"));
    }

    @Test
    @DisplayName("내 문의 삭제 — 200")
    void delete_own_returns200() throws Exception {
        mockMvc.perform(delete("/api/inquiries/12").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        verify(inquiryService).delete(4L, 12L);
    }

    @Test
    @DisplayName("없는 · 남의 문의 삭제 — 404 INQUIRY_NOT_FOUND")
    void delete_notFound_returns404() throws Exception {
        willThrow(new InquiryException(InquiryErrorCode.INQUIRY_NOT_FOUND)).given(inquiryService).delete(4L, 99L);

        mockMvc.perform(delete("/api/inquiries/99").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    private void assertInvalidInput(MockHttpServletRequestBuilder request, String body) throws Exception {
        perform(request, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(inquiryService);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.header("X-User-Id", "4").header("X-User-Roles", "BUYER")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
