package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.common.event.EventEnvelope;
import com.sesac.gojigi.settlement.common.event.SqsEventListener;
import com.sesac.gojigi.settlement.settlement.dto.event.OrderItemConfirmedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** settlement-order-events 큐의 OrderItemConfirmed → 정산 대상 적재 */
@Component
@RequiredArgsConstructor
public class OrderItemConfirmedListener implements SqsEventListener {

    private static final TypeReference<EventEnvelope<OrderItemConfirmedPayload>> TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final SettlementItemService settlementItemService;

    @Override
    public String eventType() {
        return "OrderItemConfirmed";
    }

    @Override
    public void onMessage(String envelopeJson) {
        settlementItemService.load(jsonMapper.readValue(envelopeJson, TYPE));
    }
}
