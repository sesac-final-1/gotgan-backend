package com.sesac.gojigi.review.review.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.review.dto.response.MyReviewResponse;
import com.sesac.gojigi.review.review.dto.response.WritableReviewResponse;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.service.MyReviewQueryService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MyReviewControllerTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-20T14:00:00+09:00");

    @Mock
    private MyReviewQueryService myReviewQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MyReviewController(myReviewQueryService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("내가 쓴 리뷰 — 200, 기본 page 0 · size 10, 봉투 data 에 PageResponse")
    void listMine_defaultPaging_returns200() throws Exception {
        // given
        MyReviewResponse review = new MyReviewResponse(31L, 11L, 101L, "린넨 오버핏 셔츠", "오트밀 / M",
                "https://cdn.gotgan.click/products/1/a1b2.jpg", 5, null, "오버핏이에요", ReviewFit.TRUE_TO_SIZE, true,
                null, null, CREATED);
        given(myReviewQueryService.listMine(4L, 0, 10)).willReturn(new PageResponse<>(List.of(review), 0, 10, 1, 1, false));

        // when · then
        mockMvc.perform(get("/api/reviews/me").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].reviewId").value(31))
                .andExpect(jsonPath("$.data.content[0].optionName").value("오트밀 / M"))
                .andExpect(jsonPath("$.data.content[0].blinded").value(true))
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-20T14:00:00+09:00"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @ParameterizedTest(name = "page={0} size={1}")
    @CsvSource({ "-1, 10", "0, 0", "0, 51", "a, 10" })
    @DisplayName("page · size 범위 밖 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
    void listMine_invalidPaging_returns400(String page, String size) throws Exception {
        mockMvc.perform(get("/api/reviews/me").param("page", page).param("size", size)
                        .header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(myReviewQueryService);
    }

    @Test
    @DisplayName("작성 가능한 리뷰 — 200, 봉투 data 에 목록")
    void listWritable_returns200() throws Exception {
        // given
        given(myReviewQueryService.listWritable(4L)).willReturn(List.of(new WritableReviewResponse(11L, 101L,
                "린넨 오버핏 셔츠", "오트밀 / M", null, CREATED, CREATED.plusDays(30))));

        // when · then
        mockMvc.perform(get("/api/reviews/me/writable").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderItemId").value(11))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-11-19T14:00:00+09:00"));
    }

    @Test
    @DisplayName("X-User-Id 없음 — 401 UNAUTHORIZED (게이트웨이가 GET 을 공개로 통과시킴), 서비스를 부르지 않는다")
    void withoutUserHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/reviews/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reviews/me/writable")).andExpect(status().isUnauthorized());
        verifyNoInteractions(myReviewQueryService);
    }
}
