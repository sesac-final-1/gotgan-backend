package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.common.event.EventEnvelope;
import com.sesac.gojigi.settlement.common.event.SqsEventListener;
import com.sesac.gojigi.settlement.settlement.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.settlement.settlement.exception.SettlementErrorCode;
import com.sesac.gojigi.settlement.settlement.exception.SettlementException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * settlement-store-events 큐의 StoreStatusChanged → 지급 보류 판단 상태 갱신 (옛 이벤트 무시 — docs/events.md 순서 규칙).
 * StoreApproved 보다 먼저 도착해 스냅샷이 없으면 예외 → 메시지를 지우지 않아 재전달, 그 사이 StoreApproved 가 반영되면 처리된다.
 */
@Component
@RequiredArgsConstructor
public class StoreStatusChangedListener implements SqsEventListener {

    private static final TypeReference<EventEnvelope<StoreStatusChangedPayload>> TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final StoreStatusService storeStatusService;

    @Override
    public String eventType() {
        return "StoreStatusChanged";
    }

    @Override
    public void onMessage(String envelopeJson) {
        StoreStatusService.Result result = storeStatusService.applyStatusChanged(jsonMapper.readValue(envelopeJson, TYPE));
        if (result == StoreStatusService.Result.STORE_NOT_FOUND) {
            throw new SettlementException(SettlementErrorCode.SETTLE_STORE_NOT_FOUND);
        }
    }
}
