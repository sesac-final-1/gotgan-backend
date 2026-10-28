package com.sesac.gojigi.review.review.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.review.dto.request.ProductReviewSort;
import com.sesac.gojigi.review.review.dto.response.ProductReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse.FitCount;
import com.sesac.gojigi.review.review.dto.response.RatingCount;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.service.ProductReviewQueryService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProductReviewControllerTest {

    @Mock
    private ProductReviewQueryService productReviewQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductReviewController(productReviewQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("목록 — 로그인 없이 200, 기본 sort LATEST · page 0 · size 10")
    void list_defaults_returns200() throws Exception {
        ProductReviewResponse review = new ProductReviewResponse(31L, "곳간러버", 5, null, "오버핏이에요", "오트밀 / M",
                ReviewFit.TRUE_TO_SIZE, null, null, OffsetDateTime.parse("2026-10-20T14:00:00+09:00"));
        given(productReviewQueryService.list(101L, null, ProductReviewSort.LATEST, 0, 10))
                .willReturn(new PageResponse<>(List.of(review), 0, 10, 1, 1, false));

        mockMvc.perform(get("/api/reviews").param("productId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].writerDisplayName").value("곳간러버"))
                .andExpect(jsonPath("$.data.content[0].optionName").value("오트밀 / M"))
                .andExpect(jsonPath("$.data.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-20T14:00:00+09:00"));
    }

    @Test
    @DisplayName("목록 — rating · sort · page · size 를 그대로 넘긴다")
    void list_withParams_passesThrough() throws Exception {
        given(productReviewQueryService.list(101L, 5, ProductReviewSort.RATING_LOW, 1, 20))
                .willReturn(new PageResponse<>(List.of(), 1, 20, 0, 0, false));

        mockMvc.perform(get("/api/reviews").param("productId", "101").param("rating", "5")
                        .param("sort", "RATING_LOW").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "&rating=0", "&rating=6", "&sort=HELPFUL", "&page=-1", "&size=51" })
    @DisplayName("목록 — productId 없음 · rating 1~5 밖 · 도움순 · page · size 범위 밖은 400 INVALID_INPUT")
    void list_invalidParam_returns400(String query) throws Exception {
        String url = query.isEmpty() ? "/api/reviews" : "/api/reviews?productId=101" + query;

        mockMvc.perform(get(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(productReviewQueryService);
    }

    @Test
    @DisplayName("요약 — 200, 평균 · 리뷰 수 · 분포 · 사이즈 체감")
    void summarize_returns200() throws Exception {
        given(productReviewQueryService.summarize(101L)).willReturn(new ProductReviewSummaryResponse(4.5, 2,
                List.of(new RatingCount(5, 1), new RatingCount(4, 1), new RatingCount(3, 0), new RatingCount(2, 0),
                        new RatingCount(1, 0)),
                List.of(new FitCount(ReviewFit.SMALL, 0), new FitCount(ReviewFit.TRUE_TO_SIZE, 2), new FitCount(ReviewFit.LARGE, 0))));

        mockMvc.perform(get("/api/reviews/summary").param("productId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.averageRating").value(4.5))
                .andExpect(jsonPath("$.data.reviewCount").value(2))
                .andExpect(jsonPath("$.data.fits[1].fit").value("TRUE_TO_SIZE"));
    }

    @Test
    @DisplayName("요약 — productId 없음 · 숫자 아님 400")
    void summarize_invalidProductId_returns400() throws Exception {
        mockMvc.perform(get("/api/reviews/summary")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reviews/summary").param("productId", "abc")).andExpect(status().isBadRequest());
        verifyNoInteractions(productReviewQueryService);
    }
}
