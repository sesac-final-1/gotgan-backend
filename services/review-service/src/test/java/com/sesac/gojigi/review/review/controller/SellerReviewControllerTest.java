package com.sesac.gojigi.review.review.controller;

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
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.review.dto.request.ReviewReplyRequest;
import com.sesac.gojigi.review.review.dto.request.SellerReviewTab;
import com.sesac.gojigi.review.review.dto.response.ReviewReplyResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.RatingCount;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse.TabCounts;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import com.sesac.gojigi.review.review.service.SellerReviewService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SellerReviewControllerTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-20T14:00:00+09:00");

    @Mock
    private SellerReviewService sellerReviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerReviewController(sellerReviewService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder seller(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", "2").header("X-User-Roles", "SELLER").header("X-Store-Id", "1");
    }

    @Test
    @DisplayName("목록 — 기본 tab ALL · page 0 · size 10, X-Store-Id 의 스토어로")
    void list_defaults_returns200() throws Exception {
        // given
        SellerReviewResponse review = new SellerReviewResponse(31L, 101L, "린넨 셔츠", "오트밀 / M", "곳간러버", 2, null,
                "길어요", null, false, null, null, CREATED, List.of());
        given(sellerReviewService.list(1L, SellerReviewTab.ALL, null, 0, 10))
                .willReturn(new PageResponse<>(List.of(review), 0, 10, 1, 1, false));

        // when · then
        mockMvc.perform(seller(get("/api/seller/reviews")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewId").value(31))
                .andExpect(jsonPath("$.data.content[0].writerDisplayName").value("곳간러버"))
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-20T14:00:00+09:00"));
    }

    @Test
    @DisplayName("목록 — tab · productId · page · size 를 그대로 넘긴다")
    void list_withParams_passesThrough() throws Exception {
        given(sellerReviewService.list(1L, SellerReviewTab.NO_REPLY, 101L, 2, 20))
                .willReturn(new PageResponse<>(List.of(), 2, 20, 0, 0, false));

        mockMvc.perform(seller(get("/api/seller/reviews"))
                        .param("tab", "NO_REPLY").param("productId", "101").param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({ "tab, VIDEO", "page, -1", "size, 51", "productId, abc" })
    @DisplayName("목록 — 모르는 tab · 범위 밖 page · size · 숫자 아닌 productId 는 400 INVALID_INPUT")
    void list_invalidParam_returns400(String name, String value) throws Exception {
        mockMvc.perform(seller(get("/api/seller/reviews")).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(sellerReviewService);
    }

    @Test
    @DisplayName("요약 — 200, 봉투 data 에 평균 · 분포 · 탭 숫자")
    void summarize_returns200() throws Exception {
        given(sellerReviewService.summarize(1L)).willReturn(new SellerReviewSummaryResponse(4.5, 2,
                List.of(new RatingCount(5, 1), new RatingCount(4, 1), new RatingCount(3, 0), new RatingCount(2, 0),
                        new RatingCount(1, 0)), List.of(), new TabCounts(3, 1, 0, 0)));

        mockMvc.perform(seller(get("/api/seller/reviews/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.averageRating").value(4.5))
                .andExpect(jsonPath("$.data.distribution[0].rating").value(5))
                .andExpect(jsonPath("$.data.tabCounts.all").value(3));
    }

    @Test
    @DisplayName("답글 — 200, 서비스 404 · 409 는 코드 그대로, 빈 답글 · 1,001자는 400")
    void reply_cases() throws Exception {
        given(sellerReviewService.reply(eq(1L), eq(31L), any(ReviewReplyRequest.class)))
                .willReturn(new ReviewReplyResponse(31L, "감사합니다", CREATED))
                .willThrow(new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND))
                .willThrow(new ReviewException(ReviewErrorCode.REVIEW_BLINDED));

        reply("{ \"content\": \"감사합니다\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replyContent").value("감사합니다"))
                .andExpect(jsonPath("$.data.repliedAt").value("2026-10-20T14:00:00+09:00"));
        reply("{ \"content\": \"감사합니다\" }")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        reply("{ \"content\": \"감사합니다\" }")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_BLINDED"));
        reply("{ \"content\": \" \" }").andExpect(status().isBadRequest());
        reply("{ \"content\": \"" + "가".repeat(1001) + "\" }").andExpect(status().isBadRequest());
    }

    private ResultActions reply(String body) throws Exception {
        return mockMvc.perform(seller(put("/api/seller/reviews/31/reply"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
