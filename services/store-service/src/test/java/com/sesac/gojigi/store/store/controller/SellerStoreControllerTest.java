package com.sesac.gojigi.store.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.store.dto.request.SellerStoreUpdateRequest;
import com.sesac.gojigi.store.store.dto.request.StoreImagePresignRequest;
import com.sesac.gojigi.store.store.dto.response.SellerStoreResponse;
import com.sesac.gojigi.store.store.dto.response.StoreCloseResponse;
import com.sesac.gojigi.store.store.dto.response.StoreImagePresignResponse;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.service.SellerStoreService;
import com.sesac.gojigi.store.store.service.StoreQueryService;
import com.sesac.gojigi.store.store.service.StoreStatusService;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SellerStoreControllerTest {

    @Mock
    private StoreQueryService storeQueryService;

    @Mock
    private SellerStoreService sellerStoreService;

    @Mock
    private StoreStatusService storeStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerStoreController(storeQueryService, sellerStoreService, storeStatusService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("폐점 — 200, X-Store-Id 스토어를 폐점하고 상태 CLOSED · 폐점 시각 +09:00")
    void close_whenSeller_returns200() throws Exception {
        // given
        given(storeStatusService.closeBySeller(1L))
                .willReturn(new StoreCloseResponse(1L, StoreStatus.CLOSED, OffsetDateTime.parse("2026-10-29T15:00:00+09:00")));

        // when · then
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("스토어를 폐점했습니다."))
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedAt").value("2026-10-29T15:00:00+09:00"));
    }

    @Test
    @DisplayName("X-Store-Id 가 없으면 403 FORBIDDEN, 사용자 헤더가 없으면 401 — 서비스를 부르지 않는다")
    void close_whenNoStoreOrUser_rejects() throws Exception {
        mockMvc.perform(seller(patch("/api/seller/store/close")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/seller/store/close"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        then(storeStatusService).should(never()).closeBySeller(any());
    }

    @Test
    @DisplayName("진행 중 주문 409 · 미정산 409 는 코드와 서비스가 만든 안내 문구 그대로, order · settlement 장애는 502")
    void close_whenRejected_returnsCodeAndMessage() throws Exception {
        // given
        given(storeStatusService.closeBySeller(1L)).willThrow(
                new StoreException(StoreErrorCode.STORE_ORDERS_IN_PROGRESS, "진행 중인 주문 3건이 모두 끝난 뒤 폐점할 수 있습니다."));
        given(storeStatusService.closeBySeller(2L)).willThrow(
                new StoreException(StoreErrorCode.STORE_SETTLEMENT_OUTSTANDING, "미정산 90,000원 — 지급 완료(매주 목요일) 후 폐점할 수 있습니다."));
        given(storeStatusService.closeBySeller(3L)).willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR));

        // when · then
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_ORDERS_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("진행 중인 주문 3건이 모두 끝난 뒤 폐점할 수 있습니다."));
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_SETTLEMENT_OUTSTANDING"))
                .andExpect(jsonPath("$.message").value("미정산 90,000원 — 지급 완료(매주 목요일) 후 폐점할 수 있습니다."));
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "3"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
    }

    private static MockHttpServletRequestBuilder seller(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", "2").header("X-User-Roles", "SELLER");
    }

    @Test
    @DisplayName("내 스토어 조회 — 200, X-Store-Id 스토어의 설정 값 · 이미지 URL · 개설 시각 +09:00")
    void getMyStore_whenSeller_returns200() throws Exception {
        // given
        given(storeQueryService.getSellerStore(1L)).willReturn(new SellerStoreResponse(1L, "곳간상회", "gotgan", StoreStatus.ACTIVE, null,
                "<p>소개</p>", "stores/1/a.jpg", "https://images/stores/1/a.jpg", "#5b6b4a", "010-0000-1234", 3000L, 50000L,
                "CJ대한통운", "평일 14시 이전 결제 시 당일 출고", StoreGrade.SPROUT, "홍길동", "000-00-00001",
                OffsetDateTime.parse("2026-10-15T09:00:00+09:00")));

        // when · then
        mockMvc.perform(seller(get("/api/seller/store")).header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 스토어를 조회했습니다."))
                .andExpect(jsonPath("$.data.storeName").value("곳간상회"))
                .andExpect(jsonPath("$.data.imageUrl").value("https://images/stores/1/a.jpg"))
                .andExpect(jsonPath("$.data.color").value("#5b6b4a"))
                .andExpect(jsonPath("$.data.freeShippingThreshold").value(50000))
                .andExpect(jsonPath("$.data.ownerName").value("홍길동"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-15T09:00:00+09:00"));
    }

    @Test
    @DisplayName("내 스토어 조회 — X-Store-Id 없음 403 · 없는 스토어 404")
    void getMyStore_whenNoStoreOrMissing_rejects() throws Exception {
        // given
        given(storeQueryService.getSellerStore(99L)).willThrow(new StoreException(StoreErrorCode.STORE_NOT_FOUND));

        // when · then
        mockMvc.perform(seller(get("/api/seller/store")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(seller(get("/api/seller/store")).header("X-Store-Id", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    private static final String VALID_BODY = """
            {"intro":"<p>소개</p>","imageKey":null,"color":"#5b6b4a","csPhone":"02-0000-1234","shippingFee":3000,
             "freeShippingThreshold":50000,"courier":"CJ대한통운","dispatchNote":"당일 출고"}""";

    @Test
    @DisplayName("설정 수정 — 200, 본문을 X-Store-Id 스토어에 넘기고 바뀐 설정을 돌려준다")
    void updateMyStore_whenValid_returns200() throws Exception {
        // given
        given(sellerStoreService.update(eq(1L), any(SellerStoreUpdateRequest.class))).willReturn(new SellerStoreResponse(1L, "곳간상회",
                "gotgan", StoreStatus.ACTIVE, null, "<p>소개</p>", null, null, "#5b6b4a", "02-0000-1234", 3000L, 50000L, "CJ대한통운",
                "당일 출고", StoreGrade.SPROUT, "홍길동", "000-00-00001", OffsetDateTime.parse("2026-10-15T09:00:00+09:00")));

        // when · then
        mockMvc.perform(seller(put("/api/seller/store")).header("X-Store-Id", "1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("스토어 설정을 저장했습니다."))
                .andExpect(jsonPath("$.data.csPhone").value("02-0000-1234"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"color\":\"#5b6b4a\"|\"color\":\"green\"",
            "\"csPhone\":\"02-0000-1234\"|\"csPhone\":\"0212341234\"",
            "\"shippingFee\":3000|\"shippingFee\":-1",
            "\"shippingFee\":3000|\"shippingFee\":100001",
            "\"shippingFee\":3000|\"shippingFee\":null",
            "\"freeShippingThreshold\":50000|\"freeShippingThreshold\":0",
            "\"courier\":\"CJ대한통운\"|\"courier\":\"가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다\""
    })
    @DisplayName("설정 수정 형식 오류(색 · 연락처 · 배송비 음수 · 상한 · 없음 · 무료배송 기준 0 · 택배사 31자) — 400, 서비스를 부르지 않는다")
    void updateMyStore_whenInvalid_returns400(String replace) throws Exception {
        // given
        String[] fromTo = replace.split("\\|");
        String body = VALID_BODY.replace(fromTo[0], fromTo[1]);

        // when · then
        mockMvc.perform(seller(put("/api/seller/store")).header("X-Store-Id", "1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(sellerStoreService);
    }

    @Test
    @DisplayName("설정 수정 — 폐점 409 STORE_CLOSED · X-Store-Id 없음 403")
    void updateMyStore_whenClosedOrNoStore_rejects() throws Exception {
        // given
        given(sellerStoreService.update(eq(2L), any(SellerStoreUpdateRequest.class))).willThrow(new StoreException(StoreErrorCode.STORE_CLOSED));

        // when · then
        mockMvc.perform(seller(put("/api/seller/store")).header("X-Store-Id", "2").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_CLOSED"));
        mockMvc.perform(seller(put("/api/seller/store")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("대표 이미지 업로드 주소 — 200, 형식 · 크기를 X-Store-Id 스토어로 넘기고 key · uploadUrl · 만료")
    void presignImage_whenValid_returns200() throws Exception {
        // given
        given(sellerStoreService.presignImage(eq(1L), any(StoreImagePresignRequest.class))).willReturn(new StoreImagePresignResponse(
                "stores/1/3f2a0c5e-7d1b-4c1e-9a1f-0b2c3d4e5f60.jpg", "https://s3/upload", OffsetDateTime.parse("2026-10-29T15:10:00+09:00")));

        // when · then
        mockMvc.perform(seller(post("/api/seller/store/image/presign")).header("X-Store-Id", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"size\":834512}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("stores/1/3f2a0c5e-7d1b-4c1e-9a1f-0b2c3d4e5f60.jpg"))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://s3/upload"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-29T15:10:00+09:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"contentType\":\"image/gif\",\"size\":100}",
            "{\"contentType\":\"application/pdf\",\"size\":100}",
            "{\"contentType\":\"image/jpeg\",\"size\":0}",
            "{\"contentType\":\"image/jpeg\",\"size\":10485761}",
            "{\"size\":100}",
            "{\"contentType\":\"image/jpeg\"}"
    })
    @DisplayName("대표 이미지 업로드 주소 형식 오류(gif · pdf · 0바이트 · 10MB 초과 · 형식 없음 · 크기 없음) — 400, 서비스를 부르지 않는다")
    void presignImage_whenInvalid_returns400(String body) throws Exception {
        mockMvc.perform(seller(post("/api/seller/store/image/presign")).header("X-Store-Id", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(sellerStoreService);
    }
}
