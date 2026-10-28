package com.sesac.gojigi.review.review.controller;

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
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewUpdateRequest;
import com.sesac.gojigi.review.review.dto.response.MyReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewCreateResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewResponse;
import com.sesac.gojigi.review.review.dto.response.WritableReviewResponse;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import com.sesac.gojigi.review.review.service.MyReviewQueryService;
import com.sesac.gojigi.review.review.service.ReviewService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ReviewControllerTest {

    private static final String VALID_BODY = """
            { "orderItemId": 11, "rating": 5, "title": "핏이 예뻐요",
              "content": "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.", "fit": "TRUE_TO_SIZE" }
            """;

    @Mock
    private ReviewService reviewService;

    @Mock
    private MyReviewQueryService myReviewQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService, myReviewQueryService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("리뷰 작성 — 201, 봉투 data 에 reviewId")
    void write_validRequest_returns201() throws Exception {
        // given
        given(reviewService.write(eq(4L), any(ReviewCreateRequest.class))).willReturn(new ReviewCreateResponse(31L));

        // when · then
        perform(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reviewId").value(31));
    }

    @Test
    @DisplayName("X-User-Id 없음 — 401 UNAUTHORIZED, 서비스를 부르지 않는다")
    void write_withoutUserHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(reviewService);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "상품주문 누락    | \"orderItemId\": 11,   | ",
            "별점 0           | \"rating\": 5          | \"rating\": 0",
            "별점 6           | \"rating\": 5          | \"rating\": 6",
            "본문 20자 미만   | \"content\": \"평소 | \"content\": \"짧아요\", \"x\": \"",
            "본문 공백        | \"content\": \"평소 | \"content\": \"                         \", \"x\": \"",
            "사이즈 체감 모름 | \"fit\": \"TRUE_TO_SIZE\" | \"fit\": \"BIG\""
    })
    @DisplayName("형식 위반 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
    void write_invalidField_returns400(String caseName, String from, String to) throws Exception {
        assertInvalidInput(VALID_BODY.replace(from, to == null ? "" : to));
    }

    @Test
    @DisplayName("한줄 요약 101자 · 본문 2,001자 — 400 INVALID_INPUT")
    void write_tooLongText_returns400() throws Exception {
        assertInvalidInput(VALID_BODY.replace("핏이 예뻐요", "가".repeat(101)));
        assertInvalidInput(VALID_BODY.replace("평소 M 입는데", "가".repeat(2001)));
    }

    private void assertInvalidInput(String body) throws Exception {
        perform(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("사이즈 체감 · 한줄 요약은 없어도 된다")
    void write_withoutOptionalFields_returns201() throws Exception {
        // given
        given(reviewService.write(eq(4L), any(ReviewCreateRequest.class))).willReturn(new ReviewCreateResponse(32L));

        // when · then
        perform("""
                { "orderItemId": 12, "rating": 4, "content": "당도 미쳤어요. 하나 까먹고 바로 한 박스 더 주문했어요." }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reviewId").value(32));
    }

    @Test
    @DisplayName("서비스가 던진 403 · 409 는 코드 그대로")
    void write_serviceError_returnsCode() throws Exception {
        given(reviewService.write(eq(4L), any(ReviewCreateRequest.class)))
                .willThrow(new ReviewException(ReviewErrorCode.REVIEW_NOT_ELIGIBLE))
                .willThrow(new ReviewException(ReviewErrorCode.REVIEW_WRITE_EXPIRED));

        perform(VALID_BODY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_ELIGIBLE"));
        perform(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_WRITE_EXPIRED"))
                .andExpect(jsonPath("$.message").value("리뷰 작성 기한(구매확정 후 30일)이 지났습니다."));
    }

    @Nested
    @DisplayName("내 리뷰 수정 PUT /api/reviews/{reviewId}")
    class Edit {

        private static final String EDIT_BODY = """
                { "rating": 4, "title": "길이가 조금 길어요",
                  "content": "키 160인데 기장이 살짝 길어서 수선했어요. 그 외에는 만족합니다.", "fit": "LARGE" }
                """;

        @Test
        @DisplayName("수정 — 200, 봉투 data 에 수정된 값")
        void edit_validRequest_returns200() throws Exception {
            // given
            given(reviewService.edit(eq(4L), eq(31L), any(ReviewUpdateRequest.class)))
                    .willReturn(new ReviewResponse(31L, 4, "길이가 조금 길어요", "키 160인데 기장이 살짝 길어서 수선했어요.", ReviewFit.LARGE));

            // when · then
            performEdit(EDIT_BODY)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.reviewId").value(31))
                    .andExpect(jsonPath("$.data.rating").value(4))
                    .andExpect(jsonPath("$.data.fit").value("LARGE"));
        }

        @Test
        @DisplayName("별점 6 · 본문 20자 미만 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
        void edit_invalidField_returns400() throws Exception {
            performEdit(EDIT_BODY.replace("\"rating\": 4", "\"rating\": 6"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            performEdit(EDIT_BODY.replace("키 160인데 기장이 살짝 길어서 수선했어요. 그 외에는 만족합니다.", "짧아요"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            verifyNoInteractions(reviewService);
        }

        @Test
        @DisplayName("서비스가 던진 404 · 409 는 코드 그대로")
        void edit_serviceError_returnsCode() throws Exception {
            given(reviewService.edit(eq(4L), eq(31L), any(ReviewUpdateRequest.class)))
                    .willThrow(new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND))
                    .willThrow(new ReviewException(ReviewErrorCode.REVIEW_BLINDED));

            performEdit(EDIT_BODY)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
            performEdit(EDIT_BODY)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REVIEW_BLINDED"));
        }

        private ResultActions performEdit(String body) throws Exception {
            return mockMvc.perform(put("/api/reviews/31")
                    .header("X-User-Id", "4").header("X-User-Roles", "BUYER")
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }
    }

    @Nested
    @DisplayName("내 리뷰 삭제 DELETE /api/reviews/{reviewId}")
    class Delete {

        @Test
        @DisplayName("삭제 — 200, 봉투 data 없음")
        void delete_own_returns200() throws Exception {
            mockMvc.perform(delete("/api/reviews/31").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.message").value("리뷰를 삭제했습니다."));
            verify(reviewService).delete(4L, 31L);
        }

        @Test
        @DisplayName("서비스가 던진 404 는 코드 그대로")
        void delete_notFound_returns404() throws Exception {
            willThrow(new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND)).given(reviewService).delete(4L, 31L);

            mockMvc.perform(delete("/api/reviews/31").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        }

        @Test
        @DisplayName("X-User-Id 없음 — 401 UNAUTHORIZED, 서비스를 부르지 않는다")
        void delete_withoutUserHeader_returns401() throws Exception {
            mockMvc.perform(delete("/api/reviews/31"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(reviewService);
        }
    }

    @Nested
    @DisplayName("마이페이지 GET /api/reviews/me · /me/writable")
    class MyReviews {

        private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-20T14:00:00+09:00");

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

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/reviews")
                .header("X-User-Id", "4").header("X-User-Roles", "BUYER")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
