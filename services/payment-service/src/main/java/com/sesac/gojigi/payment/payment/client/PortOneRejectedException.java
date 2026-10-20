package com.sesac.gojigi.payment.payment.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import java.util.Set;

/**
 * 포트원이 4xx 로 거절 — 응답 본문의 오류 type(포트원 V2 CancelPaymentError 등)을 함께 담는다.
 * BusinessException 이라 서킷 실패로 세지 않는다 (application.yml ignore-exceptions).
 */
public class PortOneRejectedException extends BusinessException {

    /**
     * "이미 처리됐을 수 있음"을 뜻하는 취소 거절 — 앞선 요청이 응답만 끊긴 채 성공했을 때 재요청하면 이렇게 거절된다.
     * 확정 실패로 보지 않고 포트원 결제 조회로 대조한다.
     */
    private static final Set<String> MAYBE_ALREADY_DONE = Set.of(
            "PAYMENT_ALREADY_CANCELLED",                // 전액 취소가 이미 됨
            "CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN");   // 보낸 잔액과 포트원 잔액이 다름 = 다른 취소가 먼저 반영됨

    private final String portoneType;

    public PortOneRejectedException(String message, String portoneType) {
        super(CommonErrorCode.EXTERNAL_API_ERROR, message);
        this.portoneType = portoneType;
    }

    public String getPortoneType() {
        return portoneType;
    }

    public boolean maybeAlreadyDone() {
        return portoneType != null && MAYBE_ALREADY_DONE.contains(portoneType);
    }
}
