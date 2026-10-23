package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.SqsEventListener;
import com.sesac.gojigi.user.user.dto.event.StoreApprovedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** user-store-events 큐의 StoreApproved → 판매자 전환 (SELLER 역할 · store_id) */
@Component
@RequiredArgsConstructor
public class StoreApprovedListener implements SqsEventListener {

    private static final TypeReference<EventEnvelope<StoreApprovedPayload>> TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final SellerConversionService sellerConversionService;

    @Override
    public String eventType() {
        return "StoreApproved";
    }

    @Override
    public void onMessage(String envelopeJson) {
        sellerConversionService.applyApproved(jsonMapper.readValue(envelopeJson, TYPE));
    }
}
