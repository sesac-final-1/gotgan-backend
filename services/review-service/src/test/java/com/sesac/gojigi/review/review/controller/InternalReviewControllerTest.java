package com.sesac.gojigi.review.review.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.review.dto.request.ReviewBlindRequest;
import com.sesac.gojigi.review.review.dto.response.InternalReviewResponse;
import com.sesac.gojigi.review.review.dto.response.InternalUserReviewResponse;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import com.sesac.gojigi.review.review.service.InternalReviewService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalReviewControllerTest {

    private static final InternalReviewResponse BLINDED = new InternalReviewResponse(31L, 101L, 1L, 4L, 1, "본문",
            List.of(), true, "욕설 · 비방", OffsetDateTime.parse("2026-10-20T14:00:00+09:00"));

    @Mock
    private InternalReviewService internalReviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalReviewController(internalReviewService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("조회 — 200, 사용자 헤더 없이(내부 호출), 계약 모양 그대로")
    void get_returnsContractShape() throws Exception {
        given(internalReviewService.get(31L)).willReturn(BLINDED);

        mockMvc.perform(get("/internal/reviews/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewId").value(31))
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.userId").value(4))
                .andExpect(jsonPath("$.data.imageUrls").isArray())
                .andExpect(jsonPath("$.data.blinded").value(true))
                .andExpect(jsonPath("$.data.blindReason").value("욕설 · 비방"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-20T14:00:00+09:00"));
    }

    @Test
    @DisplayName("조회 — 없는 리뷰 404 REVIEW_NOT_FOUND, 숫자 아닌 ID 400")
    void get_errors() throws Exception {
        given(internalReviewService.get(99L)).willThrow(new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));

        mockMvc.perform(get("/internal/reviews/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        mockMvc.perform(get("/internal/reviews/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("블라인드 — 200, 응답은 조회와 같은 모양")
    void changeBlind_returns200() throws Exception {
        given(internalReviewService.changeBlind(eq(31L), eq(new ReviewBlindRequest(true, "욕설 · 비방")))).willReturn(BLINDED);

        mockMvc.perform(patch("/internal/reviews/31/blind").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"blinded\": true, \"reason\": \"욕설 · 비방\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("리뷰를 블라인드했습니다."))
                .andExpect(jsonPath("$.data.blinded").value(true));
    }

    @Test
    @DisplayName("해제 — reason 이 null 이어도 200 (admin 은 reason 을 null 로 보낼 수 있다)")
    void changeBlind_unblindWithNullReason_returns200() throws Exception {
        InternalReviewResponse unblinded = new InternalReviewResponse(31L, 101L, 1L, 4L, 1, "본문", List.of(), false,
                null, BLINDED.createdAt());
        given(internalReviewService.changeBlind(eq(31L), eq(new ReviewBlindRequest(false, null)))).willReturn(unblinded);

        mockMvc.perform(patch("/internal/reviews/31/blind").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"blinded\": false, \"reason\": null }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("리뷰 블라인드를 해제했습니다."))
                .andExpect(jsonPath("$.data.blindReason").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{ \"reason\": \"욕설\" }", "{ \"blinded\": \"maybe\" }", "{ \"blinded\": true, \"reason\": \"%s\" }" })
    @DisplayName("blinded 없음 · 형식 오류 · 사유 256자 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
    void changeBlind_invalidBody_returns400(String body) throws Exception {
        mockMvc.perform(patch("/internal/reviews/31/blind").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("가".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(internalReviewService);
    }

    @Test
    @DisplayName("회원별 리뷰 — 200, 기본 page 0 · size 10, 블라인드 포함")
    void listByUser_returns200() throws Exception {
        given(internalReviewService.listByUser(4L, 0, 10)).willReturn(new PageResponse<>(List.of(
                new InternalUserReviewResponse(31L, 101L, "린넨 셔츠", 1L, 1, "본문", true, "욕설 · 비방", BLINDED.createdAt())),
                0, 10, 1, 1, false));

        mockMvc.perform(get("/internal/reviews").param("userId", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewId").value(31))
                .andExpect(jsonPath("$.data.content[0].productName").value("린넨 셔츠"))
                .andExpect(jsonPath("$.data.content[0].blinded").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "?userId=abc", "?userId=4&page=-1", "?userId=4&size=51" })
    @DisplayName("회원별 리뷰 — userId 없음 · 숫자 아님 · page · size 범위 밖은 400 INVALID_INPUT")
    void listByUser_invalidParam_returns400(String query) throws Exception {
        mockMvc.perform(get("/internal/reviews" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(internalReviewService);
    }
}
