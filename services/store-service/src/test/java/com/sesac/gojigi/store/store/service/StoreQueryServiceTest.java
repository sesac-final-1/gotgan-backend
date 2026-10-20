package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.repository.StoreRepository;
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
    @DisplayName("내부 상세는 스토어 ID · 판매자 ID · 스토어명 · 상태를 돌려준다")
    void getInternal_whenStoreExists_returnsStatus() {
        // given
        Store store = mock(Store.class);
        given(store.getId()).willReturn(1L);
        given(store.getUserId()).willReturn(2L);
        given(store.getStoreName()).willReturn("곳간상회");
        given(store.getStatus()).willReturn(StoreStatus.CLOSED);
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));

        // when
        InternalStoreResponse response = storeQueryService.getInternal(1L);

        // then
        assertThat(response).isEqualTo(new InternalStoreResponse(1L, 2L, "곳간상회", StoreStatus.CLOSED));
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
}
