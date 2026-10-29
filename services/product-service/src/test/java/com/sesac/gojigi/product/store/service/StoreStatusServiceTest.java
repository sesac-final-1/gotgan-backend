package com.sesac.gojigi.product.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.product.common.event.EventEnvelope;
import com.sesac.gojigi.product.common.event.ProcessedEvent;
import com.sesac.gojigi.product.common.event.ProcessedEventRepository;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import com.sesac.gojigi.product.store.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.product.store.entity.StoreStatus;
import com.sesac.gojigi.product.store.repository.StoreStatusRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreStatusServiceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 14, 30);

    @Mock
    private StoreStatusRepository storeStatusRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private StoreStatusService service;

    @ParameterizedTest
    @CsvSource({"SUSPENDED, false", "CLOSED, false", "ACTIVE, true", "PAUSED, false"})
    @DisplayName("첫 이벤트면 스냅샷을 만들고 상품에 반영 — ACTIVE 만 운영 중, 그 밖의 값(모르는 값 포함)은 숨김")
    void applyStatusChanged_whenFirstEvent_createsSnapshotAndUpdatesProducts(String status, boolean active) {
        // given
        given(storeStatusRepository.findForUpdate(1L)).willReturn(Optional.empty());

        // when
        StoreStatusService.Result result = service.applyStatusChanged(event("e-1", 1L, status, AT));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.APPLIED);
        ArgumentCaptor<StoreStatus> snapshot = ArgumentCaptor.forClass(StoreStatus.class);
        verify(storeStatusRepository).saveAndFlush(snapshot.capture());
        assertThat(snapshot.getValue().getStoreId()).isEqualTo(1L);
        assertThat(snapshot.getValue().getStatus()).isEqualTo(status);
        assertThat(snapshot.getValue().getStatusChangedAt()).isEqualTo(AT);
        verify(productRepository).applyStoreStatus(1L, active, AT);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("스냅샷보다 새 이벤트면 스냅샷을 고치고 상품에 반영 — 새로 만들지 않는다")
    void applyStatusChanged_whenNewer_updatesSnapshot() {
        // given
        StoreStatus snapshot = StoreStatus.of(1L, "SUSPENDED", AT.minusHours(1));
        given(storeStatusRepository.findForUpdate(1L)).willReturn(Optional.of(snapshot));

        // when
        StoreStatusService.Result result = service.applyStatusChanged(event("e-2", 1L, "ACTIVE", AT));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.APPLIED);
        assertThat(snapshot.getStatus()).isEqualTo("ACTIVE");
        assertThat(snapshot.getStatusChangedAt()).isEqualTo(AT);
        verify(storeStatusRepository, never()).saveAndFlush(any());
        verify(productRepository).applyStoreStatus(1L, true, AT);
    }

    @Test
    @DisplayName("스냅샷과 같거나 오래된 이벤트는 무시하고 처리 기록만 남긴다 — 상품을 되돌리지 않는다")
    void applyStatusChanged_whenStale_ignoresButRecords() {
        // given
        StoreStatus snapshot = StoreStatus.of(1L, "SUSPENDED", AT);
        given(storeStatusRepository.findForUpdate(1L)).willReturn(Optional.of(snapshot));

        // when
        StoreStatusService.Result result = service.applyStatusChanged(event("e-old", 1L, "ACTIVE", AT));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.STALE_IGNORED);
        assertThat(snapshot.getStatus()).isEqualTo("SUSPENDED");
        verify(productRepository, never()).applyStoreStatus(anyLong(), anyBoolean(), any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("이미 처리한 eventId 는 아무것도 하지 않는다 (재전달 중복)")
    void applyStatusChanged_whenDuplicate_doesNothing() {
        // given
        given(processedEventRepository.existsByEventId("e-1")).willReturn(true);

        // when
        StoreStatusService.Result result = service.applyStatusChanged(event("e-1", 1L, "SUSPENDED", AT));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.DUPLICATE);
        verify(storeStatusRepository, never()).findForUpdate(any());
        verify(productRepository, never()).applyStoreStatus(anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("occurredAt 은 Asia/Seoul 로 바꿔 비교 · 저장한다 — UTC 로 온 봉투도 같은 시각")
    void applyStatusChanged_whenUtcOffset_convertsToKst() {
        // given
        given(storeStatusRepository.findForUpdate(1L)).willReturn(Optional.empty());
        EventEnvelope<StoreStatusChangedPayload> utc = new EventEnvelope<>("e-utc", "StoreStatusChanged",
                OffsetDateTime.parse("2026-10-28T05:30:00Z"), "1", 1, new StoreStatusChangedPayload(1L, "SUSPENDED"));

        // when
        service.applyStatusChanged(utc);

        // then
        verify(productRepository).applyStoreStatus(1L, false, AT);
    }

    private static EventEnvelope<StoreStatusChangedPayload> event(String eventId, Long storeId, String status, LocalDateTime at) {
        return new EventEnvelope<>(eventId, "StoreStatusChanged", at.atOffset(java.time.ZoneOffset.ofHours(9)),
                String.valueOf(storeId), 1, new StoreStatusChangedPayload(storeId, status));
    }
}
