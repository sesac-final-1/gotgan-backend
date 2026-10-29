package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreSummaryResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreWithApplicant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreQueryServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private StoreQueryService storeQueryService;

    @Test
    @DisplayName("내부 상세는 스토어 ID · 판매자 ID · 스토어명 · 상태 · 배송 정책을 돌려준다")
    void getInternal_whenStoreExists_returnsStatus() {
        // given
        Store store = mock(Store.class);
        given(store.getId()).willReturn(1L);
        given(store.getUserId()).willReturn(2L);
        given(store.getStoreName()).willReturn("곳간상회");
        given(store.getStatus()).willReturn(StoreStatus.CLOSED);
        given(store.getShippingFee()).willReturn(3000L);
        given(store.getFreeShippingThreshold()).willReturn(null);
        given(store.getCourier()).willReturn("CJ대한통운");
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));

        // when
        InternalStoreResponse response = storeQueryService.getInternal(1L);

        // then
        assertThat(response).isEqualTo(new InternalStoreResponse(1L, 2L, "곳간상회", StoreStatus.CLOSED, 3000L, null, "CJ대한통운"));
    }

    @Test
    @DisplayName("없거나 소프트 삭제된 스토어는 STORE_NOT_FOUND")
    void getInternal_whenStoreMissing_throwsNotFound() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> storeQueryService.getInternal(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자 목록 한 줄은 스토어 · 신청서 값을 담고 개설 시각을 +09:00 으로 바꾼다")
    void getAdminList_withStatus_mapsSummary() {
        // given
        Store store = mock(Store.class);
        given(store.getId()).willReturn(15L);
        given(store.getStoreName()).willReturn("볕드는집");
        given(store.getSlug()).willReturn("sunny-house");
        given(store.getUserId()).willReturn(205L);
        given(store.getGrade()).willReturn(StoreGrade.SPROUT);
        given(store.getStatus()).willReturn(StoreStatus.SUSPENDED);
        given(store.getStatusReason()).willReturn("허위 원산지 표기 신고 확인");
        given(store.getCreatedAt()).willReturn(LocalDateTime.of(2025, 12, 31, 10, 0));
        given(storeRepository.findAllForAdmin(StoreStatus.SUSPENDED))
                .willReturn(List.of(new StoreWithApplicant(store, "윤하늘", "000-00-00005")));

        // when
        List<InternalStoreSummaryResponse> result = storeQueryService.getAdminList(StoreStatus.SUSPENDED);

        // then
        assertThat(result).containsExactly(new InternalStoreSummaryResponse(15L, "볕드는집", "sunny-house", 205L, "윤하늘",
                "000-00-00005", StoreGrade.SPROUT, StoreStatus.SUSPENDED, "허위 원산지 표기 신고 확인",
                OffsetDateTime.parse("2025-12-31T10:00:00+09:00")));
    }

    @Test
    @DisplayName("status 가 null 이면 그대로 넘겨 전체를 조회한다 — 없으면 빈 목록")
    void getAdminList_whenStatusNull_returnsEmpty() {
        // given
        given(storeRepository.findAllForAdmin(null)).willReturn(List.of());

        // when · then
        assertThat(storeQueryService.getAdminList(null)).isEmpty();
    }
}
