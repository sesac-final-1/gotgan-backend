package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.store.client.OrderClientAdapter;
import com.sesac.gojigi.store.store.client.SettlementClientAdapter;
import com.sesac.gojigi.store.store.client.dto.SettlementOutstandingResponse;
import com.sesac.gojigi.store.store.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.StoreCloseResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class StoreStatusServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 14, 30);

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private StoreStatusHistoryRepository storeStatusHistoryRepository;
    @Mock
    private EventOutboxService eventOutboxService;
    @Mock
    private StoreQueryService storeQueryService;
    @Mock
    private OrderClientAdapter orderClientAdapter;
    @Mock
    private SettlementClientAdapter settlementClientAdapter;

    private StoreStatusService service;

    @BeforeEach
    void setUp() {
        service = new StoreStatusService(storeRepository, storeStatusHistoryRepository, eventOutboxService, storeQueryService,
                orderClientAdapter, settlementClientAdapter, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(NOW.atZone(KST).toInstant(), KST));
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE, SUSPENDED", "SUSPENDED, ACTIVE"})
    @DisplayName("정지 · 해제하면 상태 · 사유를 바꾸고 스토어 이력(ADMIN, 사유)과 StoreStatusChanged 아웃박스 행을 남긴 뒤 1절 응답을 돌려준다")
    void changeByAdmin_whenDifferentStatus_changesAndEnqueuesEvent(StoreStatus from, StoreStatus to) {
        // given
        Store store = store(1L, from);
        given(storeRepository.findForUpdate(1L)).willReturn(Optional.of(store));
        InternalStoreResponse detail = mock(InternalStoreResponse.class);
        given(storeQueryService.getInternal(1L)).willReturn(detail);

        // when
        InternalStoreResponse response = service.changeByAdmin(1L, to, "허위 광고 신고 3건 확인");

        // then
        assertThat(response).isSameAs(detail); // 바뀐 상태 · 새 이력까지 담은 1절 응답을 상세 조회로 다시 만든다
        assertThat(store.getStatus()).isEqualTo(to);
        assertThat(store.getStatusReason()).isEqualTo("허위 광고 신고 3건 확인");

        ArgumentCaptor<StoreStatusHistory> history = ArgumentCaptor.forClass(StoreStatusHistory.class);
        verify(storeStatusHistoryRepository).save(history.capture());
        assertThat(history.getValue().getStoreId()).isEqualTo(1L);
        assertThat(history.getValue().getFromStatus()).isEqualTo(from);
        assertThat(history.getValue().getToStatus()).isEqualTo(to);
        assertThat(history.getValue().getActorType()).isEqualTo(StoreActorType.ADMIN);
        assertThat(history.getValue().getReason()).isEqualTo("허위 광고 신고 3건 확인");

        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(eventOutboxService).enqueue(eq("StoreStatusChanged"), eq("1"), eventId.capture(), eq(NOW),
                eq(new StoreStatusChangedPayload(1L, to)));
        assertThat(UUID.fromString(eventId.getValue())).isNotNull();
    }

    @Test
    @DisplayName("이미 그 상태면 아무것도 바꾸지 않고 200 — 이력 · 이벤트 없음 (멱등)")
    void changeByAdmin_whenSameStatus_isNoOp() {
        // given
        Store store = store(15L, StoreStatus.SUSPENDED);
        ReflectionTestUtils.setField(store, "statusReason", "반품 미처리");
        given(storeRepository.findForUpdate(15L)).willReturn(Optional.of(store));
        InternalStoreResponse detail = mock(InternalStoreResponse.class);
        given(storeQueryService.getInternal(15L)).willReturn(detail);

        // when
        InternalStoreResponse response = service.changeByAdmin(15L, StoreStatus.SUSPENDED, "다시 정지");

        // then
        assertThat(response).isSameAs(detail);
        assertThat(store.getStatus()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(store.getStatusReason()).isEqualTo("반품 미처리");
        verify(storeStatusHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("요청 상태가 CLOSED 면 400 INVALID_INPUT — 스토어를 조회하지 않는다 (폐점은 판매자 API)")
    void changeByAdmin_whenTargetClosed_throwsInvalidInput() {
        assertErrorCode(() -> service.changeByAdmin(1L, StoreStatus.CLOSED, "사유"), CommonErrorCode.INVALID_INPUT);
        verify(storeRepository, never()).findForUpdate(any());
    }

    @Test
    @DisplayName("폐점 스토어는 409 STORE_INVALID_STATUS_TRANSITION 이고 이력 · 이벤트를 남기지 않는다")
    void changeByAdmin_whenStoreClosed_throwsInvalidTransition() {
        // given
        given(storeRepository.findForUpdate(2L)).willReturn(Optional.of(store(2L, StoreStatus.CLOSED)));

        // when · then
        assertErrorCode(() -> service.changeByAdmin(2L, StoreStatus.ACTIVE, "사유"), StoreErrorCode.STORE_INVALID_STATUS_TRANSITION);
        verify(storeStatusHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("없거나 소프트 삭제된 스토어는 404 STORE_NOT_FOUND")
    void changeByAdmin_whenStoreMissing_throwsNotFound() {
        // given
        given(storeRepository.findForUpdate(99L)).willReturn(Optional.empty());

        // when · then
        assertErrorCode(() -> service.changeByAdmin(99L, StoreStatus.SUSPENDED, "사유"), StoreErrorCode.STORE_NOT_FOUND);
    }

    private static Store store(Long id, StoreStatus status) {
        Store store = Store.open(id * 10, 4L, "곳간상회", "gotgan", null, "010-0000-1234");
        ReflectionTestUtils.setField(store, "id", id);
        ReflectionTestUtils.setField(store, "status", status);
        return store;
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE", "SUSPENDED"})
    @DisplayName("폐점 — 진행 중 주문 0 · 미정산 없음이면 잠근 행을 CLOSED, 이력(SELLER) · StoreStatusChanged{CLOSED} · 폐점 시각")
    void closeBySeller_whenClear_closesAndEnqueuesEvent(StoreStatus from) {
        // given
        Store store = store(1L, from);
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        given(orderClientAdapter.inProgressCount(1L)).willReturn(0L);
        given(settlementClientAdapter.outstanding(1L)).willReturn(new SettlementOutstandingResponse(1L, 0, 0, 0, true));
        given(storeRepository.findForUpdate(1L)).willReturn(Optional.of(store));

        // when
        StoreCloseResponse response = service.closeBySeller(1L);

        // then
        assertThat(response).isEqualTo(new StoreCloseResponse(1L, StoreStatus.CLOSED, OffsetDateTime.parse("2026-10-28T14:30:00+09:00")));
        assertThat(store.getStatus()).isEqualTo(StoreStatus.CLOSED);
        ArgumentCaptor<StoreStatusHistory> history = ArgumentCaptor.forClass(StoreStatusHistory.class);
        verify(storeStatusHistoryRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(from);
        assertThat(history.getValue().getToStatus()).isEqualTo(StoreStatus.CLOSED);
        assertThat(history.getValue().getActorType()).isEqualTo(StoreActorType.SELLER);
        assertThat(history.getValue().getReason()).isNull();
        verify(eventOutboxService).enqueue(eq("StoreStatusChanged"), eq("1"), anyString(), eq(NOW),
                eq(new StoreStatusChangedPayload(1L, StoreStatus.CLOSED)));
    }

    @Test
    @DisplayName("진행 중 주문이 있으면 409 STORE_ORDERS_IN_PROGRESS(건수 안내) — settlement 를 부르지 않고 잠그지 않는다")
    void closeBySeller_whenOrdersInProgress_throwsWithCount() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store(1L, StoreStatus.ACTIVE)));
        given(orderClientAdapter.inProgressCount(1L)).willReturn(3L);

        // when · then
        assertThatThrownBy(() -> service.closeBySeller(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("진행 중인 주문 3건이 모두 끝난 뒤 폐점할 수 있습니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(StoreErrorCode.STORE_ORDERS_IN_PROGRESS);
        verify(settlementClientAdapter, never()).outstanding(any());
        verify(storeRepository, never()).findForUpdate(any());
    }

    @Test
    @DisplayName("미정산이 있으면 409 STORE_SETTLEMENT_OUTSTANDING — 금액은 집계 전 · 지급 예정 · 보류의 합")
    void closeBySeller_whenSettlementOutstanding_throwsWithAmount() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store(1L, StoreStatus.ACTIVE)));
        given(orderClientAdapter.inProgressCount(1L)).willReturn(0L);
        given(settlementClientAdapter.outstanding(1L)).willReturn(new SettlementOutstandingResponse(1L, 30_000, 48_000, 12_000, false));

        // when · then
        assertThatThrownBy(() -> service.closeBySeller(1L))
                .hasMessage("미정산 90,000원 — 지급 완료(매주 목요일) 후 폐점할 수 있습니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(StoreErrorCode.STORE_SETTLEMENT_OUTSTANDING);
        verify(storeRepository, never()).findForUpdate(any());
    }

    @Test
    @DisplayName("order · settlement 장애(어댑터 폴백 502)면 폐점하지 않는다 — 확인할 수 없으면 거절")
    void closeBySeller_whenRemoteUnavailable_rejects() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store(1L, StoreStatus.ACTIVE)));
        given(orderClientAdapter.inProgressCount(1L)).willReturn(0L);
        given(settlementClientAdapter.outstanding(1L))
                .willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "settlement 미정산 조회에 실패했습니다."));

        // when · then
        assertErrorCode(() -> service.closeBySeller(1L), CommonErrorCode.EXTERNAL_API_ERROR);
        verify(storeRepository, never()).findForUpdate(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("이미 폐점이면 다른 서비스를 부르지 않고 처음 폐점 시각으로 200 (멱등)")
    void closeBySeller_whenAlreadyClosed_returnsFirstClosedAt() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(store(2L, StoreStatus.CLOSED)));
        StoreStatusHistory closed = StoreStatusHistory.of(2L, StoreStatus.ACTIVE, StoreStatus.CLOSED, StoreActorType.SELLER, null);
        ReflectionTestUtils.setField(closed, "createdAt", LocalDateTime.of(2026, 10, 20, 9, 0));
        given(storeStatusHistoryRepository.findAllByStoreIdOrderByIdAsc(2L)).willReturn(List.of(closed));

        // when
        StoreCloseResponse response = service.closeBySeller(2L);

        // then
        assertThat(response.closedAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T09:00:00+09:00"));
        verify(orderClientAdapter, never()).inProgressCount(any());
        verify(settlementClientAdapter, never()).outstanding(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("확인하는 사이 다른 요청이 먼저 폐점했으면 잠근 뒤 아무것도 남기지 않는다")
    void closeBySeller_whenClosedConcurrently_writesNothing() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store(1L, StoreStatus.ACTIVE)));
        given(orderClientAdapter.inProgressCount(1L)).willReturn(0L);
        given(settlementClientAdapter.outstanding(1L)).willReturn(new SettlementOutstandingResponse(1L, 0, 0, 0, true));
        given(storeRepository.findForUpdate(1L)).willReturn(Optional.of(store(1L, StoreStatus.CLOSED)));

        // when
        service.closeBySeller(1L);

        // then
        verify(storeStatusHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("없거나 소프트 삭제된 스토어 폐점은 404 STORE_NOT_FOUND")
    void closeBySeller_whenStoreMissing_throwsNotFound() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

        // when · then
        assertErrorCode(() -> service.closeBySeller(99L), StoreErrorCode.STORE_NOT_FOUND);
    }
}
