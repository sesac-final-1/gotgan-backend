package com.sesac.gojigi.review.inquiry.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.SellerInquiryTab;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryCreateResponse;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.ProductInquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquirySummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import com.sesac.gojigi.review.inquiry.service.InquiryService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Nested
    @DisplayName("조회")
    class Read {

        private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-29T10:00:00+09:00");

        @Test
        @DisplayName("상품 문의 — 비로그인은 viewer null, 비밀글은 내용 null 그대로")
        void listForProduct_anonymous() throws Exception {
            // given
            given(inquiryService.listForProduct(101L, null, null, 0, 10)).willReturn(new PageResponse<>(List.of(
                    new ProductInquiryResponse(12L, "곳간러버", true, false, null, true, null, AT, AT)), 0, 10, 1, 1, false));

            // when · then
            mockMvc.perform(get("/api/inquiries?productId=101"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].secret").value(true))
                    .andExpect(jsonPath("$.data.content[0].content").doesNotExist())
                    .andExpect(jsonPath("$.data.content[0].answered").value(true))
                    .andExpect(jsonPath("$.data.content[0].writerDisplayName").value("곳간러버"));
        }

        @Test
        @DisplayName("상품 문의 — 로그인 헤더(X-User-Id · X-Store-Id)를 viewer 로 넘긴다")
        void listForProduct_withViewerHeaders() throws Exception {
            given(inquiryService.listForProduct(101L, 4L, 7L, 1, 20)).willReturn(new PageResponse<>(List.of(), 1, 20, 0, 0, false));

            mockMvc.perform(get("/api/inquiries?productId=101&page=1&size=20")
                            .header("X-User-Id", "4").header("X-User-Roles", "SELLER").header("X-Store-Id", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(1));
            verify(inquiryService).listForProduct(101L, 4L, 7L, 1, 20);
        }

        @ParameterizedTest
        @ValueSource(strings = { "/api/inquiries", "/api/inquiries?productId=101&page=-1", "/api/inquiries?productId=101&size=51",
                "/api/inquiries/me?size=0", "/api/seller/inquiries?tab=NEW", "/api/seller/inquiries?size=51" })
        @DisplayName("productId 없음 · page · size 범위 밖 · 모르는 tab — 400 INVALID_INPUT")
        void list_invalidParam_returns400(String url) throws Exception {
            mockMvc.perform(get(url).header("X-User-Id", "4").header("X-User-Roles", "SELLER").header("X-Store-Id", "7"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            verifyNoInteractions(inquiryService);
        }

        @Test
        @DisplayName("내 문의 — 로그인 회원으로, 헤더 없으면 401")
        void listMine() throws Exception {
            given(inquiryService.listMine(4L, 0, 10)).willReturn(new PageResponse<>(List.of(
                    new InquiryResponse(12L, 101L, "린넨 오버핏 셔츠", null, "재입고 언제 되나요?", false, "다음 주", AT, AT)), 0, 10, 1, 1, false));

            mockMvc.perform(get("/api/inquiries/me").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].productName").value("린넨 오버핏 셔츠"))
                    .andExpect(jsonPath("$.data.content[0].answerContent").value("다음 주"));
            mockMvc.perform(get("/api/inquiries/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("판매자 문의 — X-Store-Id 스토어 · tab 그대로")
        void listForSeller() throws Exception {
            given(inquiryService.listForSeller(7L, SellerInquiryTab.UNANSWERED, 0, 10)).willReturn(new PageResponse<>(List.of(
                    new SellerInquiryResponse(12L, 101L, "린넨 오버핏 셔츠", "곳간러버", "배송지 변경 가능할까요?", true, null, null, AT)),
                    0, 10, 1, 1, false));

            mockMvc.perform(get("/api/seller/inquiries?tab=UNANSWERED")
                            .header("X-User-Id", "9").header("X-User-Roles", "SELLER").header("X-Store-Id", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].content").value("배송지 변경 가능할까요?"))
                    .andExpect(jsonPath("$.data.content[0].secret").value(true))
                    .andExpect(jsonPath("$.data.content[0].writerDisplayName").value("곳간러버"));
        }

        @Test
        @DisplayName("판매자 탭 숫자 — 200, 스토어 정보 없으면 서비스의 403 그대로")
        void summarizeForSeller() throws Exception {
            given(inquiryService.summarizeForSeller(7L)).willReturn(new SellerInquirySummaryResponse(3, 12, 15));
            given(inquiryService.summarizeForSeller(null)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다."));

            mockMvc.perform(get("/api/seller/inquiries/summary")
                            .header("X-User-Id", "9").header("X-User-Roles", "SELLER").header("X-Store-Id", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unanswered").value(3))
                    .andExpect(jsonPath("$.data.answered").value(12))
                    .andExpect(jsonPath("$.data.all").value(15));
            mockMvc.perform(get("/api/seller/inquiries/summary").header("X-User-Id", "9").header("X-User-Roles", "SELLER"))
                    .andExpect(status().isForbidden());
        }
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
