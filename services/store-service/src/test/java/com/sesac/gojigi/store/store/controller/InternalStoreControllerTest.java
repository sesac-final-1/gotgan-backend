package com.sesac.gojigi.store.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreSummaryResponse;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.service.StoreQueryService;
import com.sesac.gojigi.store.store.service.StoreStatusService;
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
class InternalStoreControllerTest {

    @Mock
    private StoreQueryService storeQueryService;

    @Mock
    private StoreStatusService storeStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalStoreController(storeQueryService, storeStatusService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("스토어 상세 조회 — 200, 봉투 data 에 storeId · userId · storeName · status · 배송 정책")
    void get_whenStoreExists_returns200() throws Exception {
        // given
        given(storeQueryService.getInternal(1L)).willReturn(
                new InternalStoreResponse(1L, 2L, "곳간상회", StoreStatus.ACTIVE, 3000L, 30000L, "우체국택배"));

        // when · then
        mockMvc.perform(get("/internal/stores/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.storeName").value("곳간상회"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.freeShippingThreshold").value(30000))
                .andExpect(jsonPath("$.data.courier").value("우체국택배"));
    }

    @Test
    @DisplayName("무료배송 기준 · 택배사가 없으면 키는 남고 값이 null — 호출자가 null 을 \"무료배송 없음\"으로 읽는다")
    void get_whenNoFreeShippingAndCourier_returnsNullFields() throws Exception {
        // given
        given(storeQueryService.getInternal(3L)).willReturn(
                new InternalStoreResponse(3L, 6L, "새 스토어", StoreStatus.ACTIVE, 3000L, null, null));

        // when · then
        mockMvc.perform(get("/internal/stores/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.freeShippingThreshold").isEmpty())
                .andExpect(jsonPath("$.data.courier").isEmpty());
    }

    @Test
    @DisplayName("없는 스토어 — 404 STORE_NOT_FOUND, data 는 null")
    void get_whenStoreMissing_returns404() throws Exception {
        // given
        given(storeQueryService.getInternal(99L)).willThrow(new StoreException(StoreErrorCode.STORE_NOT_FOUND));

        // when · then
        mockMvc.perform(get("/internal/stores/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("storeId 가 숫자가 아니면 400 — 서비스를 부르지 않는다")
    void get_whenStoreIdNotNumber_returns400() throws Exception {
        mockMvc.perform(get("/internal/stores/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("관리자 스토어 목록 — 200 배열, 상태 필터를 서비스에 넘기고 시각은 +09:00")
    void list_withStatus_returns200Array() throws Exception {
        // given
        given(storeQueryService.getAdminList(StoreStatus.SUSPENDED)).willReturn(List.of(new InternalStoreSummaryResponse(
                15L, "볕드는집", "sunny-house", 205L, "윤하늘", "000-00-00005", StoreGrade.SPROUT, StoreStatus.SUSPENDED,
                "허위 원산지 표기 신고 확인", OffsetDateTime.parse("2025-12-31T10:00:00+09:00"))));

        // when · then
        mockMvc.perform(get("/internal/stores").param("status", "SUSPENDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("스토어 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data[0].storeId").value(15))
                .andExpect(jsonPath("$.data[0].ownerName").value("윤하늘"))
                .andExpect(jsonPath("$.data[0].bizRegNo").value("000-00-00005"))
                .andExpect(jsonPath("$.data[0].grade").value("SPROUT"))
                .andExpect(jsonPath("$.data[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2025-12-31T10:00:00+09:00"));
    }

    @Test
    @DisplayName("관리자 스토어 목록 status 없음 — 전체(null), 없으면 빈 배열")
    void list_withoutStatus_returnsEmptyArray() throws Exception {
        // given
        given(storeQueryService.getAdminList(null)).willReturn(List.of());

        // when · then
        mockMvc.perform(get("/internal/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("관리자 스토어 목록 모르는 status(DORMANT) — 400 INVALID_INPUT, 서비스를 부르지 않는다")
    void list_whenStatusUnknown_returns400() throws Exception {
        mockMvc.perform(get("/internal/stores").param("status", "DORMANT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        then(storeQueryService).should(never()).getAdminList(any());
    }

    @Test
    @DisplayName("정지 — 200 스토어 상세(1절), 상태 · 사유를 서비스에 넘긴다")
    void changeStatus_whenSuspend_returns200Detail() throws Exception {
        // given
        given(storeStatusService.changeByAdmin(1L, StoreStatus.SUSPENDED, "허위 광고 신고 3건 확인"))
                .willReturn(new InternalStoreResponse(1L, 2L, "곳간상회", StoreStatus.SUSPENDED, 3000L, null, null));

        // when · then
        mockMvc.perform(patch("/internal/stores/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"허위 광고 신고 3건 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("스토어 상태를 변경했습니다."))
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"reason\":\"사유\"}",
            "{\"status\":\"DORMANT\",\"reason\":\"사유\"}",
            "{\"status\":\"SUSPENDED\"}",
            "{\"status\":\"SUSPENDED\",\"reason\":\" \"}"
    })
    @DisplayName("정지 · 해제 형식 오류(상태 없음 · 모르는 값, 사유 없음 · 공백) — 400 INVALID_INPUT 이고 서비스를 부르지 않는다")
    void changeStatus_whenInvalidBody_returns400(String body) throws Exception {
        mockMvc.perform(patch("/internal/stores/1/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        then(storeStatusService).should(never()).changeByAdmin(any(), any(), any());
    }

    @Test
    @DisplayName("정지 · 해제 사유 256자 — 400 INVALID_INPUT")
    void changeStatus_whenReasonTooLong_returns400() throws Exception {
        mockMvc.perform(patch("/internal/stores/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"" + "가".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("폐점 스토어 — 409 STORE_INVALID_STATUS_TRANSITION / 없는 스토어 — 404 STORE_NOT_FOUND")
    void changeStatus_whenClosedOrMissing_returnsDomainError() throws Exception {
        // given
        given(storeStatusService.changeByAdmin(2L, StoreStatus.ACTIVE, "사유"))
                .willThrow(new StoreException(StoreErrorCode.STORE_INVALID_STATUS_TRANSITION));
        given(storeStatusService.changeByAdmin(99L, StoreStatus.ACTIVE, "사유"))
                .willThrow(new StoreException(StoreErrorCode.STORE_NOT_FOUND));
        String body = "{\"status\":\"ACTIVE\",\"reason\":\"사유\"}";

        // when · then
        mockMvc.perform(patch("/internal/stores/2/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_INVALID_STATUS_TRANSITION"));
        mockMvc.perform(patch("/internal/stores/99/status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }
}
