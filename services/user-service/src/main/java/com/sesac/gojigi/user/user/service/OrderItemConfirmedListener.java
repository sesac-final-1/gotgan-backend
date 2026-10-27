package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.SqsEventListener;
import com.sesac.gojigi.user.user.dto.event.OrderItemConfirmedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** user-order-events 큐의 OrderItemConfirmed → 누적 구매확정 금액(회원등급 기준) · 포인트 적립 */
@Component
@RequiredArgsConstructor
public class OrderItemConfirmedListener implements SqsEventListener {

    private static final TypeReference<EventEnvelope<OrderItemConfirmedPayload>> TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final PurchaseConfirmedService purchaseConfirmedService;

    @Override
    public String eventType() {
        return "OrderItemConfirmed";
    }

    @Override
    public void onMessage(String envelopeJson) {
        purchaseConfirmedService.apply(jsonMapper.readValue(envelopeJson, TYPE));
    }
}
