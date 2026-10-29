package com.sesac.gojigi.product.store.service;

import com.sesac.gojigi.product.common.event.EventEnvelope;
import com.sesac.gojigi.product.common.event.SqsEventListener;
import com.sesac.gojigi.product.store.dto.event.StoreStatusChangedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** product-store-events 큐의 StoreStatusChanged → 스토어 상태 스냅샷 · 상품 노출 갱신 (옛 이벤트 무시 — docs/events.md 순서 규칙) */
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
        storeStatusService.applyStatusChanged(jsonMapper.readValue(envelopeJson, TYPE));
    }
}
