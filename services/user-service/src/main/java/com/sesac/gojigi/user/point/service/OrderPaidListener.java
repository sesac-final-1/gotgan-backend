package com.sesac.gojigi.user.point.service;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.SqsEventListener;
import com.sesac.gojigi.user.point.dto.event.OrderPaidPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** user-order-events 큐의 OrderPaid → 상품주문별 포인트 적립 */
@Component
@RequiredArgsConstructor
public class OrderPaidListener implements SqsEventListener {

    private static final TypeReference<EventEnvelope<OrderPaidPayload>> TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final PointService pointService;

    @Override
    public String eventType() {
        return "OrderPaid";
    }

    @Override
    public void onMessage(String envelopeJson) {
        pointService.earn(jsonMapper.readValue(envelopeJson, TYPE));
    }
}
