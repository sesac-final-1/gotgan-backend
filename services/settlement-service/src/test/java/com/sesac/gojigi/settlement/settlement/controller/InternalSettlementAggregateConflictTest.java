package com.sesac.gojigi.settlement.settlement.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.settlement.settlement.dto.request.AggregateRequest;
import com.sesac.gojigi.settlement.settlement.exception.SettlementErrorCode;
import com.sesac.gojigi.settlement.settlement.exception.SettlementException;
import com.sesac.gojigi.settlement.settlement.service.SettlementCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

/** 동시 집계 충돌은 500 이 아니라 409 — 집계는 멱등이라 다시 누르면 된다 (B-구-11) */
@ExtendWith(MockitoExtension.class)
class InternalSettlementAggregateConflictTest {

    private static final AggregateRequest WEEK = new AggregateRequest("2026-W44");

    @Mock SettlementCommandService settlementCommandService;
    @InjectMocks InternalSettlementController controller;

    @Test
    @DisplayName("스토어 × 주차 UK 충돌 → SETTLE_AGGREGATE_CONFLICT")
    void uniqueConflict() {
        given(settlementCommandService.aggregate(any(), any())).willThrow(new DataIntegrityViolationException("uk_settlement"));

        assertThatThrownBy(() -> controller.aggregate(WEEK))
                .isInstanceOf(SettlementException.class)
                .extracting("errorCode").isEqualTo(SettlementErrorCode.SETTLE_AGGREGATE_CONFLICT);
    }

    @Test
    @DisplayName("잠금 충돌도 SETTLE_AGGREGATE_CONFLICT")
    void lockConflict() {
        given(settlementCommandService.aggregate(any(), any())).willThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> controller.aggregate(WEEK))
                .isInstanceOf(SettlementException.class)
                .extracting("errorCode").isEqualTo(SettlementErrorCode.SETTLE_AGGREGATE_CONFLICT);
    }
}
