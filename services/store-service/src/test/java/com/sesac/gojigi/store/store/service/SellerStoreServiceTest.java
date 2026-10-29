package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.store.dto.request.SellerStoreUpdateRequest;
import com.sesac.gojigi.store.store.dto.response.SellerStoreResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SellerStoreServiceTest {

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private StoreQueryService storeQueryService;

    @InjectMocks
    private SellerStoreService service;

    @Test
    @DisplayName("수정 — 잠근 행에 설정을 넣고(소개는 정화) 바뀐 설정을 조회해 돌려준다")
    void update_whenActive_updatesLockedStoreAndReturnsDetail() {
        // given
        Store store = store(1L, StoreStatus.ACTIVE, null);
        given(storeRepository.findForUpdate(1L)).willReturn(Optional.of(store));
        SellerStoreResponse detail = mock(SellerStoreResponse.class);
        given(storeQueryService.getSellerStore(1L)).willReturn(detail);

        // when
        SellerStoreResponse response = service.update(1L, request("<p>소개<script>x</script></p>", "stores/1/new-key.webp", "#2B2B2B"));

        // then
        assertThat(response).isSameAs(detail);
        assertThat(store.getIntro()).isEqualTo("<p>소개</p>");
        assertThat(store.getImageKey()).isEqualTo("stores/1/new-key.webp");
        assertThat(store.getColor()).isEqualTo("#2b2b2b");
        assertThat(store.getCsPhone()).isEqualTo("02-0000-1234");
        assertThat(store.getShippingFee()).isEqualTo(2500L);
        assertThat(store.getFreeShippingThreshold()).isEqualTo(50000L);
        assertThat(store.getCourier()).isEqualTo("CJ대한통운");
        assertThat(store.getDispatchNote()).isNull();   // 공백뿐이면 null
    }

    @Test
    @DisplayName("폐점 스토어 수정은 409 STORE_CLOSED — 정지 중이면 수정된다")
    void update_whenClosed_throwsStoreClosed() {
        // given
        given(storeRepository.findForUpdate(2L)).willReturn(Optional.of(store(2L, StoreStatus.CLOSED, null)));
        Store suspended = store(3L, StoreStatus.SUSPENDED, null);
        given(storeRepository.findForUpdate(3L)).willReturn(Optional.of(suspended));

        // when · then
        assertThatThrownBy(() -> service.update(2L, request(null, null, "#5b6b4a")))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(StoreErrorCode.STORE_CLOSED);
        service.update(3L, request(null, null, "#5b6b4a"));
        assertThat(suspended.getShippingFee()).isEqualTo(2500L);
        verify(storeQueryService, never()).getSellerStore(2L);
    }

    @Test
    @DisplayName("없거나 소프트 삭제된 스토어는 404")
    void update_whenMissing_throwsNotFound() {
        // given
        given(storeRepository.findForUpdate(99L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> service.update(99L, request(null, null, "#5b6b4a")))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
        verify(storeQueryService, never()).getSellerStore(any());
    }

    @Test
    @DisplayName("이미지 키 — 비우면 없음, 지금 키는 모양과 상관없이 유지, 내 stores/{storeId}/ 키(jpg · png · webp)만 새로")
    void checkImageKey_acceptsNoneCurrentAndOwnKey() {
        assertThat(SellerStoreService.checkImageKey(1L, null, "stores/1/a.jpg")).isNull();
        assertThat(SellerStoreService.checkImageKey(1L, "  ", "stores/1/a.jpg")).isNull();
        assertThat(SellerStoreService.checkImageKey(1L, "stores/seed/old.jpeg", "stores/seed/old.jpeg")).isEqualTo("stores/seed/old.jpeg");
        assertThat(SellerStoreService.checkImageKey(1L, "stores/1/7f3c9e2a-b1.png", null)).isEqualTo("stores/1/7f3c9e2a-b1.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stores/2/a.jpg", "products/1/a.jpg", "stores/1/a.gif", "stores/1/../2/a.jpg", "https://evil/stores/1/a.jpg"})
    @DisplayName("이미지 키 — 다른 스토어 · 다른 폴더 · 허용 안 된 확장자 · 경로 장난은 400 INVALID_INPUT")
    void checkImageKey_rejectsForeignOrInvalidKey(String key) {
        assertThatThrownBy(() -> SellerStoreService.checkImageKey(1L, key, null))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
    }

    private static Store store(Long id, StoreStatus status, String imageKey) {
        Store store = Store.open(id * 10, 4L, "곳간상회", "gotgan", null, "010-0000-1234");
        ReflectionTestUtils.setField(store, "id", id);
        ReflectionTestUtils.setField(store, "status", status);
        ReflectionTestUtils.setField(store, "imageKey", imageKey);
        return store;
    }

    private static SellerStoreUpdateRequest request(String intro, String imageKey, String color) {
        return new SellerStoreUpdateRequest(intro, imageKey, color, "02-0000-1234", 2500L, 50000L, " CJ대한통운 ", "  ");
    }
}
