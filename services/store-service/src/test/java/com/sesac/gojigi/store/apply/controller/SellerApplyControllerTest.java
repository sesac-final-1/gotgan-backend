package com.sesac.gojigi.store.apply.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.apply.dto.request.ApplicationResubmitRequest;
import com.sesac.gojigi.store.apply.dto.request.DocumentPresignRequest;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest;
import com.sesac.gojigi.store.apply.dto.response.ApplicationResubmitResponse;
import com.sesac.gojigi.store.apply.dto.response.DocumentPresignResponse;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.History;
import com.sesac.gojigi.store.apply.dto.response.MyApplicationResponse;
import com.sesac.gojigi.store.apply.dto.response.SellerApplyResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.service.ApplicationQueryService;
import com.sesac.gojigi.store.apply.service.SellerApplyService;
import com.sesac.gojigi.store.apply.service.SellerDocumentPresignService;
import java.time.LocalDateTime;
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

    private static final String PRESIGN_BODY = """
            { "files": [ { "docType": "BUSINESS_REG", "contentType": "application/pdf", "size": 482113 } ] }
            """;

    @Mock
    private SellerApplyService sellerApplyService;

    @Mock
    private SellerDocumentPresignService sellerDocumentPresignService;

    @Mock
    private ApplicationQueryService applicationQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerApplyController(sellerApplyService, applicationQueryService, sellerDocumentPresignService))
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

    @Test
    @DisplayName("서류 업로드 주소 발급 — 200, data 는 key · uploadUrl · expiresAt 목록")
    void presignDocuments_validRequest_returns200() throws Exception {
        // given
        given(sellerDocumentPresignService.presign(eq(4L), any(DocumentPresignRequest.class)))
                .willReturn(List.of(new DocumentPresignResponse("applications/4/a.pdf", "https://s3.example/put",
                        OffsetDateTime.parse("2026-10-26T15:10:00+09:00"))));

        // when · then
        performPresign(PRESIGN_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].key").value("applications/4/a.pdf"))
                .andExpect(jsonPath("$.data[0].uploadUrl").value("https://s3.example/put"))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-10-26T15:10:00+09:00"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "files 누락      | { }",
            "files 안 null   | { \"files\": [ null ] }",
            "서류 종류 모름  | { \"files\": [ { \"docType\": \"CERT\", \"contentType\": \"application/pdf\", \"size\": 1 } ] }",
            "형식 빈 값      | { \"files\": [ { \"docType\": \"BANK_COPY\", \"contentType\": \" \", \"size\": 1 } ] }",
            "크기 누락       | { \"files\": [ { \"docType\": \"BANK_COPY\", \"contentType\": \"image/png\" } ] }"
    })
    @DisplayName("서류 업로드 주소 발급 형식 위반 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
    void presignDocuments_invalidField_returns400(String caseName, String body) throws Exception {
        performPresign(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(sellerDocumentPresignService);
    }

    @Test
    @DisplayName("서류 업로드 주소 발급 — X-User-Id 없음 401, 서비스를 부르지 않는다")
    void presignDocuments_withoutUserHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/seller/apply/documents/presign").contentType(MediaType.APPLICATION_JSON).content(PRESIGN_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sellerDocumentPresignService);
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

    private ResultActions performPresign(String body) throws Exception {
        return mockMvc.perform(post("/api/seller/apply/documents/presign")
                .header(CurrentUserArgumentResolver.USER_ID_HEADER, "4")
                .header(CurrentUserArgumentResolver.USER_ROLES_HEADER, "BUYER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("내 신청 상태 — 200, X-User-Id 본인 신청의 상태 · 사유 · 보완 기한 · 이력")
    void getMine_whenApplied_returns200() throws Exception {
        // given
        OffsetDateTime submitted = OffsetDateTime.parse("2026-10-22T14:05:11+09:00");
        OffsetDateTime supplemented = OffsetDateTime.parse("2026-10-23T10:00:00+09:00");
        given(applicationQueryService.getMine(4L)).willReturn(new MyApplicationResponse(27L, "AP-2610-027", ApplicationStatus.SUPPLEMENT,
                "곳간상회", "gotgan", "사본이 흐립니다", submitted, supplemented, OffsetDateTime.parse("2026-10-30T10:00:00+09:00"), null, null,
                List.of(new History(null, ApplicationStatus.PENDING, ActorType.APPLICANT, null, submitted),
                        new History(ApplicationStatus.PENDING, ApplicationStatus.SUPPLEMENT, ActorType.ADMIN, "사본이 흐립니다", supplemented)),
                List.of(DocType.ECOMMERCE_REG)));

        // when · then
        mockMvc.perform(get("/api/seller/apply/me").header("X-User-Id", "4").header("X-User-Roles", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationNo").value("AP-2610-027"))
                .andExpect(jsonPath("$.data.status").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.data.statusReason").value("사본이 흐립니다"))
                .andExpect(jsonPath("$.data.supplementDueAt").value("2026-10-30T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.reapplyAvailableAt").value(nullValue()))
                .andExpect(jsonPath("$.data.history[1].toStatus").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.data.requestedDocs[0]").value("ECOMMERCE_REG"));
    }

    @Test
    @DisplayName("신청한 적이 없으면 200 data null — 오류가 아니라 화면이 신청 폼을 보여 줌")
    void getMine_whenNeverApplied_returnsNullData() throws Exception {
        // given
        given(applicationQueryService.getMine(6L)).willReturn(null);

        // when · then
        mockMvc.perform(get("/api/seller/apply/me").header("X-User-Id", "6").header("X-User-Roles", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("사용자 헤더가 없으면 401 — 서비스를 부르지 않는다")
    void getMine_whenNoUser_returns401() throws Exception {
        mockMvc.perform(get("/api/seller/apply/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(applicationQueryService);
    }

    // ── 보완 재제출 (2-3절) ──

    private static final String RESUBMIT_BODY = """
            {
              "companyName": "새상호",
              "settlementAccount": { "bank": "신한", "number": "111-111", "holder": "홍길동" },
              "documents": [
                { "docType": "ECOMMERCE_REG", "key": "applications/4/ecom2.pdf", "originalName": "신고증.pdf" }
              ]
            }
            """;

    @Test
    @DisplayName("보완 재제출 — 200, 경로의 신청 ID · X-User-Id 로 서비스 호출, 봉투 data 에 PENDING · resubmittedAt")
    void resubmit_validRequest_returns200() throws Exception {
        // given
        given(sellerApplyService.resubmit(eq(4L), eq(27L), any(ApplicationResubmitRequest.class))).willReturn(
                new ApplicationResubmitResponse(27L, ApplicationStatus.PENDING, OffsetDateTime.parse("2026-10-29T16:20:00+09:00")));

        // when · then
        performResubmit(RESUBMIT_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationId").value(27))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.resubmittedAt").value("2026-10-29T16:20:00+09:00"));
    }

    @Test
    @DisplayName("보완 재제출 — 빈 본문 {} 도 200(내용 보완 확인만), 보내지 않은 항목은 null 로 넘어감")
    void resubmit_emptyBody_passesNulls() throws Exception {
        // given
        given(sellerApplyService.resubmit(eq(4L), eq(27L), any(ApplicationResubmitRequest.class))).willReturn(
                new ApplicationResubmitResponse(27L, ApplicationStatus.PENDING, OffsetDateTime.parse("2026-10-29T16:20:00+09:00")));

        // when · then
        performResubmit("{}").andExpect(status().isOk());
        then(sellerApplyService).should().resubmit(eq(4L), eq(27L),
                argThat(r -> r.companyName() == null && r.documentsOrEmpty().isEmpty() && r.settlementAccount() == null));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "상호 공백           | {\"companyName\": \"  \"}",
            "휴대폰 형식         | {\"managerPhone\": \"01000001234\"}",
            "이메일 형식         | {\"managerEmail\": \"seller\"}",
            "카테고리 0          | {\"categoryId\": 0}",
            "대표 상품 1개       | {\"representativeProducts\": [{\"name\": \"셔츠\", \"subCategory\": \"셔츠\", \"price\": 1}]}",
            "계좌 예금주 없음    | {\"settlementAccount\": {\"bank\": \"신한\", \"number\": \"1\", \"holder\": \"\"}}",
            "서류 종류 없음      | {\"documents\": [{\"key\": \"applications/4/a.pdf\", \"originalName\": \"a.pdf\"}]}"
    })
    @DisplayName("보완 재제출 형식 오류 — 400 INVALID_INPUT, 서비스를 부르지 않는다")
    void resubmit_invalidBody_returns400(String name, String body) throws Exception {
        performResubmit(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(sellerApplyService);
    }

    @Test
    @DisplayName("보완 재제출 — 서비스의 404 · 409 를 그대로")
    void resubmit_serviceErrors_passThrough() throws Exception {
        // given
        given(sellerApplyService.resubmit(eq(4L), eq(27L), any(ApplicationResubmitRequest.class)))
                .willThrow(new ApplyException(ApplyErrorCode.APPLY_NOT_FOUND))
                .willThrow(new ApplyException(ApplyErrorCode.APPLY_INVALID_STATUS, "보완 기한이 지나 다시 제출할 수 없습니다."));

        // when · then
        performResubmit("{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("APPLY_NOT_FOUND"));
        performResubmit("{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLY_INVALID_STATUS"))
                .andExpect(jsonPath("$.message").value("보완 기한이 지나 다시 제출할 수 없습니다."));
    }

    private ResultActions performResubmit(String body) throws Exception {
        return mockMvc.perform(patch("/api/seller/apply/27/resubmit").header("X-User-Id", "4").header("X-User-Roles", "BUYER")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
