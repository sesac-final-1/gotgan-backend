package com.sesac.gojigi.store.apply.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest;
import com.sesac.gojigi.store.apply.dto.response.SellerApplyResponse;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.service.SellerApplyService;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SellerApplyControllerTest {

    private static final String VALID_BODY = """
            {
              "bizType": "INDIVIDUAL",
              "companyName": "곳간상회",
              "ownerName": "홍길동",
              "bizRegNo": "000-00-00001",
              "ecommerceRegNo": "2026-서울성동-0000",
              "managerPhone": "010-0000-1234",
              "managerEmail": "seller@example.com",
              "businessAddress": "서울시 성동구",
              "storeName": "Linen Lab",
              "slug": "linen-lab",
              "categoryId": 1,
              "intro": "<p>소개</p>",
              "representativeProducts": [
                { "name": "리넨 셔츠", "subCategory": "셔츠", "price": 39000 },
                { "name": "리넨 팬츠", "subCategory": "팬츠", "price": 45000 }
              ],
              "expectedMonthlySales": "FROM_5M_TO_20M",
              "salesChannel": null,
              "documents": [
                { "docType": "BUSINESS_REG", "key": "applications/4/biz.pdf", "originalName": "biz.pdf" }
              ],
              "settlementAccount": { "bank": "국민", "number": "000000-00-000000", "holder": "홍길동" },
              "agreements": { "sellerTerms": true, "electronicFinance": true, "privacy": true }
            }
            """;

    @Mock
    private SellerApplyService sellerApplyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerApplyController(sellerApplyService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("입점 신청 — 201, 봉투 data 에 applicationId · applicationNo · status · submittedAt")
    void apply_validRequest_returns201() throws Exception {
        // given
        given(sellerApplyService.apply(eq(4L), any(SellerApplyRequest.class)))
                .willReturn(SellerApplyResponse.of(27L, ApplicationStatus.PENDING, LocalDateTime.of(2026, 10, 22, 14, 5, 11)));

        // when · then
        perform(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.applicationId").value(27))
                .andExpect(jsonPath("$.data.applicationNo").value("AP-2610-027"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-10-22T14:05:11+09:00"));
    }

    @Test
    @DisplayName("X-User-Id 없음 — 401 UNAUTHORIZED, 서비스를 부르지 않는다")
    void apply_withoutUserHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/seller/apply").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(sellerApplyService);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "사업자번호 형식   | \"bizRegNo\": \"000-00-00001\"           | \"bizRegNo\": \"0000000001\"",
            "휴대폰 형식       | \"managerPhone\": \"010-0000-1234\"      | \"managerPhone\": \"01000001234\"",
            "이메일 형식       | \"managerEmail\": \"seller@example.com\" | \"managerEmail\": \"seller\"",
            "slug 대문자       | \"slug\": \"linen-lab\"                  | \"slug\": \"Linen-Lab\"",
            "slug - 로 시작    | \"slug\": \"linen-lab\"                  | \"slug\": \"-linen\"",
            "slug 2자          | \"slug\": \"linen-lab\"                  | \"slug\": \"ab\"",
            "slug 예약어       | \"slug\": \"linen-lab\"                  | \"slug\": \"admin\"",
            "카테고리 0        | \"categoryId\": 1                         | \"categoryId\": 0",
            "사업자 구분 모름  | \"bizType\": \"INDIVIDUAL\"               | \"bizType\": \"PERSON\"",
            "상호 빈 값        | \"companyName\": \"곳간상회\"             | \"companyName\": \" \"",
            "필수 약관 미동의  | \"privacy\": true                         | \"privacy\": false",
            "필수 약관 누락    | \"privacy\": true                         | \"privacyAgreed\": true",
            "예상 판매가 0     | \"price\": 45000                          | \"price\": 0"
    })
    @DisplayName("형식 위반 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
    void apply_invalidField_returns400(String caseName, String from, String to) throws Exception {
        assertInvalidInput(VALID_BODY.replace(from, to));
    }

    @Test
    @DisplayName("판매 예정 상품이 1개면 400 INVALID_INPUT (2~10개)")
    void apply_singleRepresentativeProduct_returns400() throws Exception {
        assertInvalidInput(VALID_BODY.replace(
                ",\n    { \"name\": \"리넨 팬츠\", \"subCategory\": \"팬츠\", \"price\": 45000 }", ""));
    }

    @Test
    @DisplayName("서비스가 던진 409 는 코드 · 메시지 그대로")
    void apply_serviceConflict_returns409() throws Exception {
        // given
        given(sellerApplyService.apply(eq(4L), any(SellerApplyRequest.class)))
                .willThrow(new ApplyException(ApplyErrorCode.APPLY_REAPPLY_RESTRICTED, "2026-10-31 부터 신청할 수 있습니다."));

        // when · then
        perform(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLY_REAPPLY_RESTRICTED"))
                .andExpect(jsonPath("$.message").value("2026-10-31 부터 신청할 수 있습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private void assertInvalidInput(String body) throws Exception {
        assertThat(body).isNotEqualTo(VALID_BODY);   // 바꿀 문자열을 못 찾아 정상 본문이 그대로 가는 실수 방지
        perform(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(sellerApplyService);
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/seller/apply")
                .header(CurrentUserArgumentResolver.USER_ID_HEADER, "4")
                .header(CurrentUserArgumentResolver.USER_ROLES_HEADER, "BUYER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
