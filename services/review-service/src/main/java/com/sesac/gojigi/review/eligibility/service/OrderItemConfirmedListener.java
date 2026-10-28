package com.sesac.gojigi.review.eligibility.service;

import com.sesac.gojigi.review.common.event.EventEnvelope;
import com.sesac.gojigi.review.common.event.SqsEventListener;
import com.sesac.gojigi.review.eligibility.dto.event.OrderItemConfirmedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** review-order-events 큐의 OrderItemConfirmed → 리뷰 작성 자격 저장 (REV-01) */
@Component
@RequiredArgsConstructor
public class OrderItemConfirmedListener implements SqsEventListener {

    private static final TypeReference<EventEnvelope<OrderItemConfirmedPayload>> TYPE = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final ReviewEligibilityService reviewEligibilityService;

    @Override
    public String eventType() {
        return "OrderItemConfirmed";
    }

    @Override
    public void onMessage(String envelopeJson) {
        reviewEligibilityService.grant(jsonMapper.readValue(envelopeJson, TYPE));
    }
}
