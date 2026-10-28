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
import com.sesac.gojigi.review.review.dto.request.ProductReviewSort;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewUpdateRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewImagePresignRequest;
import com.sesac.gojigi.review.review.dto.response.ReviewImagePresignResponse;
import com.sesac.gojigi.review.review.dto.response.MyReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductRatingResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse.FitCount;
import com.sesac.gojigi.review.review.dto.response.RatingCount;
import com.sesac.gojigi.review.review.dto.response.ReviewCreateResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewResponse;
import com.sesac.gojigi.review.review.dto.response.StoreRatingResponse;
import com.sesac.gojigi.review.review.dto.response.WritableReviewResponse;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import com.sesac.gojigi.review.review.service.MyReviewQueryService;
import com.sesac.gojigi.review.review.service.ProductReviewQueryService;
import com.sesac.gojigi.review.review.service.RatingQueryService;
import com.sesac.gojigi.review.review.service.ReviewImagePresignService;
import com.sesac.gojigi.review.review.service.ReviewService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Mock
    private ProductReviewQueryService productReviewQueryService;

    @Mock
    private RatingQueryService ratingQueryService;

    @Mock
    private ReviewImagePresignService reviewImagePresignService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService, myReviewQueryService, productReviewQueryService, ratingQueryService,
                        reviewImagePresignService))
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
                    .willReturn(new ReviewResponse(31L, 4, "길이가 조금 길어요", "키 160인데 기장이 살짝 길어서 수선했어요.", ReviewFit.LARGE, List.of()));

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
                    null, null, CREATED, List.of());
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

    @Nested
    @DisplayName("상품 리뷰 (공개) GET /api/reviews · /summary")
    class ProductReviews {

        @Test
        @DisplayName("목록 — 로그인 없이 200, 기본 sort LATEST · page 0 · size 10")
        void list_defaults_returns200() throws Exception {
            ProductReviewResponse review = new ProductReviewResponse(31L, "곳간러버", 5, null, "오버핏이에요", "오트밀 / M",
                    ReviewFit.TRUE_TO_SIZE, null, null, OffsetDateTime.parse("2026-10-20T14:00:00+09:00"), List.of());
            given(productReviewQueryService.list(101L, null, false, ProductReviewSort.LATEST, 0, 10))
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
            given(productReviewQueryService.list(101L, 5, false, ProductReviewSort.RATING_LOW, 1, 20))
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

    @Nested
    @DisplayName("여러 상품 · 스토어 평점 (공개) GET /api/reviews/ratings/products · /stores")
    class Ratings {

        @Test
        @DisplayName("상품 — 로그인 없이 200, ids=1,2 · ids=1&ids=2 둘 다 받는다")
        void rateProducts_bothIdFormats_returns200() throws Exception {
            given(ratingQueryService.rateProducts(List.of(101L, 102L))).willReturn(
                    List.of(new ProductRatingResponse(101L, 4.5, 2), new ProductRatingResponse(102L, 0, 0)));

            mockMvc.perform(get("/api/reviews/ratings/products").param("ids", "101,102"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].productId").value(101))
                    .andExpect(jsonPath("$.data[0].averageRating").value(4.5))
                    .andExpect(jsonPath("$.data[1].reviewCount").value(0));
            mockMvc.perform(get("/api/reviews/ratings/products").param("ids", "101").param("ids", "102"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[1].productId").value(102));
        }

        @Test
        @DisplayName("스토어 — 200")
        void rateStores_returns200() throws Exception {
            given(ratingQueryService.rateStores(List.of(1L))).willReturn(List.of(new StoreRatingResponse(1L, 4.71, 128)));

            mockMvc.perform(get("/api/reviews/ratings/stores").param("ids", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].storeId").value(1))
                    .andExpect(jsonPath("$.data[0].reviewCount").value(128));
        }

        @ParameterizedTest
        @ValueSource(strings = { "/api/reviews/ratings/products", "/api/reviews/ratings/stores" })
        @DisplayName("ids 없음 · 숫자 아님 · 101개 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
        void invalidIds_returns400(String path) throws Exception {
            String ids101 = LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(","));

            mockMvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            mockMvc.perform(get(path).param("ids", "abc")).andExpect(status().isBadRequest());
            mockMvc.perform(get(path).param("ids", ids101)).andExpect(status().isBadRequest());
            verifyNoInteractions(ratingQueryService);
        }
    }

    @Nested
    @DisplayName("사진 업로드 주소 POST /api/reviews/images/presign")
    class Presign {

        @Test
        @DisplayName("발급 — 200, 봉투 data 에 키 · 주소 · 만료 시각")
        void presign_returns200() throws Exception {
            given(reviewImagePresignService.presign(eq(4L), any(ReviewImagePresignRequest.class))).willReturn(List.of(
                    new ReviewImagePresignResponse("reviews/4/a.jpg", "https://s3/put", OffsetDateTime.parse("2026-10-28T15:10:00+09:00"))));

            mockMvc.perform(post("/api/reviews/images/presign").header("X-User-Id", "4").header("X-User-Roles", "BUYER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"files\": [ { \"contentType\": \"image/jpeg\", \"size\": 834512 } ] }"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].key").value("reviews/4/a.jpg"))
                    .andExpect(jsonPath("$.data[0].uploadUrl").value("https://s3/put"))
                    .andExpect(jsonPath("$.data[0].expiresAt").value("2026-10-28T15:10:00+09:00"));
        }

        @Test
        @DisplayName("files 없음 · 크기 없음 400 INVALID_INPUT, 서비스 400 REVIEW_IMAGE_INVALID 는 메시지 그대로, 헤더 없음 401")
        void presign_errors() throws Exception {
            mockMvc.perform(post("/api/reviews/images/presign").header("X-User-Id", "4")
                            .contentType(MediaType.APPLICATION_JSON).content("{ }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            mockMvc.perform(post("/api/reviews/images/presign").header("X-User-Id", "4")
                            .contentType(MediaType.APPLICATION_JSON).content("{ \"files\": [ { \"contentType\": \"image/jpeg\" } ] }"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/reviews/images/presign")
                            .contentType(MediaType.APPLICATION_JSON).content("{ \"files\": [] }"))
                    .andExpect(status().isUnauthorized());

            given(reviewImagePresignService.presign(eq(4L), any(ReviewImagePresignRequest.class)))
                    .willThrow(new ReviewException(ReviewErrorCode.REVIEW_IMAGE_INVALID, "1번째 파일: 사진은 10MB 이하만 올릴 수 있습니다."));
            mockMvc.perform(post("/api/reviews/images/presign").header("X-User-Id", "4")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"files\": [ { \"contentType\": \"image/jpeg\", \"size\": 99999999 } ] }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REVIEW_IMAGE_INVALID"))
                    .andExpect(jsonPath("$.message").value("1번째 파일: 사진은 10MB 이하만 올릴 수 있습니다."));
        }
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/reviews")
                .header("X-User-Id", "4").header("X-User-Roles", "BUYER")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
