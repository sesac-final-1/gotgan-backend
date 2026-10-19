package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.common.event.EventEnvelope;
import com.sesac.gojigi.settlement.common.event.SqsEventListener;
import com.sesac.gojigi.settlement.settlement.dto.event.StoreApprovedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** settlement-store-events 큐의 StoreApproved → 정산용 스토어 스냅샷 */
@Component
@RequiredArgsConstructor
public class StoreApprovedListener implements SqsEventListener {

    private static final TypeReference<EventEnvelope<StoreApprovedPayload>> TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final StoreSnapshotService storeSnapshotService;

    @Override
    public String eventType() {
        return "StoreApproved";
    }

    @Override
    public void onMessage(String envelopeJson) {
        storeSnapshotService.applyApproved(jsonMapper.readValue(envelopeJson, TYPE));
    }
}
