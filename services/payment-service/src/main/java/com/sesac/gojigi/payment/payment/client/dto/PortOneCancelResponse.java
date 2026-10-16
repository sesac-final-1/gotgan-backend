package com.sesac.gojigi.payment.payment.client.dto;

import java.time.OffsetDateTime;

/** 포트원 V2 결제 취소 응답 — cancellation.status: SUCCEEDED / REQUESTED / FAILED */
public record PortOneCancelResponse(Cancellation cancellation) {

    public record Cancellation(String status, String id, long totalAmount, OffsetDateTime cancelledAt) {
    }
}
