package com.sesac.gojigi.payment.payment.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.payment.payment.client.PortOneClientAdapter;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.dto.request.CancelPaymentRequest;
import com.sesac.gojigi.payment.payment.dto.request.PreparePaymentRequest;
import com.sesac.gojigi.payment.payment.dto.request.VerifyPaymentRequest;
import com.sesac.gojigi.payment.payment.dto.response.PaymentCancelResponse;
import com.sesac.gojigi.payment.payment.dto.response.PaymentResponse;
import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentStatus;
import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import com.sesac.gojigi.payment.payment.exception.PaymentException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 결제 준비 · 검증 · 취소 · 웹훅 반영 (docs/api/payment.md). 트랜잭션 없음 — 포트원 호출 → PaymentTxService 짧은 쓰기 순서.
 * PAID 판정은 포트원 조회 결과로만 한다. 브라우저 응답 · 웹훅 본문은 "확인 요청" 신호일 뿐이다.
 */
@Slf4j
@Service
public class PaymentService {

    static final String MISMATCH_REASON = "결제 금액 불일치 자동 취소";
    static final String LATE_PAYMENT_REASON = "결제 실패 처리 후 승인된 결제 자동 취소";

    private final PaymentTxService paymentTx;
    private final PortOneClientAdapter portOne;
    private final Counter mismatchCounter;

    public PaymentService(PaymentTxService paymentTx, PortOneClientAdapter portOne, MeterRegistry meterRegistry) {
        this.paymentTx = paymentTx;
        this.portOne = portOne;
        this.mismatchCounter = Counter.builder("payment.mismatch")   // Prometheus: payment_mismatch_total (스펙 관측 지표)
                .description("결제 금액 불일치로 자동 취소한 건수")
                .register(meterRegistry);
    }

    /** 1. 결제 준비 — orderNo 멱등 */
    public PaymentTxService.Prepared prepare(PreparePaymentRequest request) {
        try {
            return paymentTx.prepare(request);
        } catch (DataIntegrityViolationException e) {
            return paymentTx.prepare(request);   // 같은 orderNo 동시 요청 — 먼저 저장된 행 기준으로 다시 판단
        }
    }

    /** 2. 결제 검증 — order 가 계산한 기대 금액과 포트원 결제를 비교 */
    public PaymentResponse verify(VerifyPaymentRequest request) {
        Payment payment = paymentTx.getByOrderNo(request.orderNo());
        if (payment.getStatus().isCancellable()) {
            return PaymentResponse.from(payment);   // 이미 반영됨 (웹훅이 먼저 왔거나 재시도)
        }
        if (payment.getStatus().isFinal()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INVALID_STATUS);
        }
        PortOnePayment portOnePayment = portOne.getPayment(payment.getPortonePaymentId())
                .filter(PortOnePayment::isPaid)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_COMPLETED));
        if (portOnePayment.totalAmount() != payment.getAmount() || payment.getAmount() != request.amount()) {
            refundMismatch(payment, portOnePayment, request.amount());
            throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        return markPaid(payment, portOnePayment);
    }

    /** 3. 결제 취소 — 금액 생략 = 전액(주문 Saga 보상), 금액 있음 = 부분. sagaId 멱등 */
    public PaymentCancelResponse cancel(String orderNo, CancelPaymentRequest request) {
        Optional<PaymentCancelResponse> done = paymentTx.findCancelBySagaId(request.sagaId());
        if (done.isPresent()) {
            return done.get();
        }
        return request.isFull() ? cancelAll(orderNo, request) : cancelPartial(orderNo, request);
    }

    /** 4. 결제 상태 조회 (결제 대기 만료 스케줄러) — 포트원을 부르지 않는다 */
    public PaymentResponse get(String orderNo) {
        return PaymentResponse.from(paymentTx.getByOrderNo(orderNo));
    }

    /**
     * 5. 웹훅 — 포트원을 다시 조회해 반영. 웹훅은 재전송되므로 예외 없이 끝나는 경우가 대부분이다.
     * READY + 포트원 PAID → 검증과 같은 비교 / FAILED + 포트원 PAID(만료 후 늦은 결제) → 포트원 취소
     */
    public void reconcile(String portonePaymentId) {
        Optional<Payment> found = paymentTx.findByPortonePaymentId(portonePaymentId);
        if (found.isEmpty()) {
            log.info("웹훅 무시 - 모르는 paymentId={}", portonePaymentId);
            return;
        }
        Payment payment = found.get();
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.FAILED) {
            return;   // 이미 반영됨 · 우리가 요청한 취소의 알림
        }
        Optional<PortOnePayment> portOnePayment = portOne.getPayment(portonePaymentId).filter(PortOnePayment::isPaid);
        if (portOnePayment.isEmpty()) {
            return;   // 결제창 열림 · 실패 알림 — 확정은 결제 대기 만료(order)
        }
        PortOnePayment paid = portOnePayment.get();
        if (payment.getStatus() == PaymentStatus.FAILED) {
            log.warn("결제 실패 처리 후 승인된 결제 - orderNo={}, amount={}", payment.getOrderNo(), paid.totalAmount());
            refund(payment, null, paid, LATE_PAYMENT_REASON);
        } else if (paid.totalAmount() != payment.getAmount()) {
            refundMismatch(payment, paid, payment.getAmount());
        } else {
            markPaid(payment, paid);
        }
    }

    private PaymentCancelResponse cancelAll(String orderNo, CancelPaymentRequest request) {
        Optional<Payment> found = paymentTx.findByOrderNo(orderNo);
        if (found.isEmpty()) {
            return null;   // 결제 준비 전에 실패 — 되돌릴 것 없음
        }
        Payment payment = found.get();
        return switch (payment.getStatus()) {
            case FAILED, CANCELLED -> PaymentCancelResponse.of(payment, null);
            case READY -> {
                // 타임아웃 등으로 결과를 모르는 상태 — 포트원에 결제가 있으면 취소까지 끝내고 FAILED
                Optional<PortOnePayment> paid = portOne.getPayment(payment.getPortonePaymentId()).filter(PortOnePayment::isPaid);
                yield paid.isPresent()
                        ? refund(payment, request.sagaId(), paid.get(), request.reason())
                        : paymentTx.markFailed(payment.getId());
            }
            case PAID, PARTIAL_CANCELLED -> {
                long remaining = payment.remainingAmount();
                PortOneCancelResponse.Cancellation cancellation = cancelAtPortOne(payment,
                        PortOneCancelRequest.full(request.reason(), remaining));
                yield paymentTx.recordCancel(payment.getId(), request.sagaId(), null, remaining, request.reason(), cancellation);
            }
        };
    }

    private PaymentCancelResponse cancelPartial(String orderNo, CancelPaymentRequest request) {
        Payment payment = paymentTx.getByOrderNo(orderNo);
        if (!payment.getStatus().isCancellable()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INVALID_STATUS);
        }
        long remaining = payment.remainingAmount();
        if (request.amount() > remaining) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED);
        }
        PortOneCancelResponse.Cancellation cancellation = cancelAtPortOne(payment,
                PortOneCancelRequest.partial(request.amount(), request.reason(), remaining));
        try {
            return paymentTx.recordCancel(payment.getId(), request.sagaId(), request.orderItemId(), request.amount(),
                    request.reason(), cancellation);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
            // 같은 sagaId 동시 요청 — 두 번째 포트원 취소는 currentCancellableAmount 불일치로 거절되므로 여기 오는 쪽이 기록 경합에서 진 것
            return paymentTx.findCancelBySagaId(request.sagaId()).orElseThrow(() -> e);
        }
    }

    private PaymentResponse markPaid(Payment payment, PortOnePayment portOnePayment) {
        try {
            return paymentTx.markPaid(payment.getId(), portOnePayment);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 확인 요청과 웹훅이 동시에 반영 — 다른 쪽이 먼저 PAID 로 바꿨으면 그 결과를 돌려준다
            Payment latest = paymentTx.getByOrderNo(payment.getOrderNo());
            if (latest.isPaid()) {
                return PaymentResponse.from(latest);
            }
            throw e;
        }
    }

    private void refundMismatch(Payment payment, PortOnePayment portOnePayment, long expectedAmount) {
        log.warn("결제 금액 불일치 - orderNo={}, expected={}, prepared={}, actual={}",
                payment.getOrderNo(), expectedAmount, payment.getAmount(), portOnePayment.totalAmount());
        mismatchCounter.increment();
        refund(payment, null, portOnePayment, MISMATCH_REASON);
    }

    /** 성립하면 안 되는 결제를 포트원에서 전액 취소하고 FAILED 로 기록 */
    private PaymentCancelResponse refund(Payment payment, String sagaId, PortOnePayment portOnePayment, String reason) {
        long cancellable = portOnePayment.cancellableAmount();
        PortOneCancelResponse.Cancellation cancellation = cancelAtPortOne(payment, PortOneCancelRequest.full(reason, cancellable));
        return paymentTx.recordRefundAndFail(payment.getId(), sagaId, cancellable, reason, cancellation);
    }

    private PortOneCancelResponse.Cancellation cancelAtPortOne(Payment payment, PortOneCancelRequest request) {
        PortOneCancelResponse.Cancellation cancellation = portOne.cancel(payment.getPortonePaymentId(), request).cancellation();
        if (cancellation == null || "FAILED".equals(cancellation.status())) {
            log.error("포트원 취소 실패 응답 - orderNo={}, status={}", payment.getOrderNo(),
                    cancellation == null ? null : cancellation.status());
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "포트원 취소에 실패했습니다.");
        }
        return cancellation;
    }
}
