package com.sesac.gojigi.review.review.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.review.review.dto.response.ProductRatingResponse;
import com.sesac.gojigi.review.review.dto.response.StoreRatingResponse;
import com.sesac.gojigi.review.review.service.RatingQueryService;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
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
class RatingControllerTest {

    @Mock
    private RatingQueryService ratingQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RatingController(ratingQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

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
