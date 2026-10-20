package com.sesac.gojigi.payment.payment.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.payment.payment.client.PortOneClientAdapter;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.dto.request.CancelPaymentRequest;
import com.sesac.gojigi.payment.payment.dto.request.PreparePaymentRequest;
import com.sesac.gojigi.payment.payment.dto.request.VerifyPaymentRequest;
import com.sesac.gojigi.payment.payment.dto.response.PaymentCancelResponse;
import com.sesac.gojigi.payment.payment.dto.response.PaymentResponse;
import com.sesac.gojigi.payment.payment.entity.CancelStatus;
import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentStatus;
import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import com.sesac.gojigi.payment.payment.exception.PaymentException;
import com.sesac.gojigi.payment.payment.service.PaymentCancelExecutor.Result;
import com.sesac.gojigi.payment.payment.service.PaymentTxService.Begun;
import com.sesac.gojigi.payment.payment.service.PaymentTxService.CancelRef;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 결제 흐름 — DB 쓰기는 PaymentTxService(짧은 트랜잭션), 포트원 취소 · 확정은 PaymentCancelExecutor.
 * 모든 포트원 호출 · DB 쓰기가 실패할 수 있다고 보고, 어느 지점에서 끊겨도 "돈과 기록이 어긋난 채 끝나는" 경로가 없게 한다:
 * - 취소 · 환불은 포트원 호출 전에 PENDING 선기록 → 결과 미확정이면 대조 → 그래도 모르면 복구 스케줄러가 마무리
 * - 성립하면 안 되는 결제(금액 불일치 · 늦은 결제)는 FAILED 확정과 환불 선기록을 한 트랜잭션에 — 환불이 끊겨도 스케줄러가 끝까지 돌려준다
 */
@Slf4j
@Service
public class PaymentService {

    static final String MISMATCH_REASON = "결제 금액 불일치 자동 취소";
    static final String LATE_PAYMENT_REASON = "결제 실패 처리 후 승인된 결제 자동 취소";

    private final PaymentTxService paymentTx;
    private final PortOneClientAdapter portOne;
    private final PaymentCancelExecutor cancelExecutor;
    private final Counter mismatchCounter;

    public PaymentService(PaymentTxService paymentTx, PortOneClientAdapter portOne, PaymentCancelExecutor cancelExecutor,
            MeterRegistry meterRegistry) {
        this.paymentTx = paymentTx;
        this.portOne = portOne;
        this.cancelExecutor = cancelExecutor;
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

    /**
     * 3. 결제 취소 — 금액 생략 = 전액(주문 Saga 보상), 금액 있음 = 부분. sagaId 멱등.
     * 결과 미확정이면 409 PAYMENT_CANCEL_PENDING — order 는 같은 sagaId 로 다시 요청한다 (그사이 복구 스케줄러가 확정할 수 있음).
     */
    public PaymentCancelResponse cancel(String orderNo, CancelPaymentRequest request) {
        Optional<CancelRef> existing = paymentTx.findCancelBySagaId(request.sagaId());
        if (existing.isPresent()) {
            return continueCancel(existing.get());
        }
        try {
            return request.isFull() ? cancelAll(orderNo, request) : cancelPartial(orderNo, request);
        } catch (DataIntegrityViolationException e) {
            // 같은 sagaId 동시 요청 — 선기록 경합에서 진 쪽. 먼저 남은 행 기준으로 이어서 처리
            return continueCancel(paymentTx.findCancelBySagaId(request.sagaId()).orElseThrow(() -> e));
        }
    }

    /** 4. 결제 상태 조회 (결제 대기 만료 스케줄러) — 포트원을 부르지 않는다 */
    public PaymentResponse get(String orderNo) {
        return PaymentResponse.from(paymentTx.getByOrderNo(orderNo));
    }

    /**
     * 5. 웹훅 — 포트원을 다시 조회해 반영. 웹훅은 재전송되므로 예외 없이 끝나는 경우가 대부분이다.
     * 먼저 이 결제의 미확정 취소를 마무리하고(취소 완료 · 실패 알림), 그다음
     * READY + 포트원 PAID → 검증과 같은 비교 / FAILED + 포트원 PAID(만료 후 늦은 결제) → 환불
     */
    public void reconcile(String portonePaymentId) {
        Optional<Payment> found = paymentTx.findByPortonePaymentId(portonePaymentId);
        if (found.isEmpty()) {
            log.info("웹훅 무시 - 모르는 paymentId={}", portonePaymentId);
            return;
        }
        Payment payment = found.get();
        paymentTx.pendingCancelsOf(payment.getId()).forEach(cancelExecutor::reconcileOnly);
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.FAILED) {
            return;   // 이미 반영됨 · 우리가 요청한 취소의 알림 (위에서 미확정 취소는 정리)
        }
        Optional<PortOnePayment> portOnePayment = portOne.getPayment(portonePaymentId).filter(PortOnePayment::isPaid);
        if (portOnePayment.isEmpty()) {
            return;   // 결제창 열림 · 실패 알림 — 확정은 결제 대기 만료(order)
        }
        PortOnePayment paid = portOnePayment.get();
        if (payment.getStatus() == PaymentStatus.FAILED) {
            log.warn("결제 실패 처리 후 승인된 결제 - orderNo={}, amount={}", payment.getOrderNo(), paid.totalAmount());
            refundInBackground(payment, paid, LATE_PAYMENT_REASON);
        } else if (paid.totalAmount() != payment.getAmount()) {
            refundMismatch(payment, paid, payment.getAmount());
        } else {
            markPaid(payment, paid);
        }
    }

    /** 이미 선기록된 취소(같은 sagaId 재요청 · 동시 요청 경합)를 이어서 처리 */
    private PaymentCancelResponse continueCancel(CancelRef cancel) {
        return switch (cancel.status()) {
            case SUCCEEDED -> paymentTx.cancelResponse(cancel.cancelId());
            case PENDING -> toResponse(cancelExecutor.resume(cancel));
            case FAILED -> {
                // 포트원이 확정 거절했던 취소 — 최신 잔액으로 다시. 조회가 실패하면 502 (선기록 상태는 그대로 FAILED)
                PortOnePayment latest = portOne.getPayment(cancel.portonePaymentId())
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "포트원에 결제가 없습니다."));
                yield toResponse(cancelExecutor.execute(paymentTx.retryFailed(cancel.cancelId(), latest.cancellableAmount())));
            }
        };
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
                // 타임아웃 등으로 결과를 모르는 상태 — 포트원에 결제가 있으면 FAILED 확정 + 환불, 없으면 FAILED
                Optional<PortOnePayment> paid = portOne.getPayment(payment.getPortonePaymentId()).filter(PortOnePayment::isPaid);
                if (paid.isEmpty()) {
                    yield paymentTx.markFailed(payment.getId());
                }
                Begun begun = paymentTx.beginRefund(payment.getId(), request.sagaId(), paid.get().cancellableAmount(),
                        request.reason());
                yield toResponse(begun.created() ? cancelExecutor.execute(begun.cancel()) : cancelExecutor.resume(begun.cancel()));
            }
            case PAID, PARTIAL_CANCELLED -> toResponse(cancelExecutor.execute(
                    paymentTx.beginCancel(payment.getId(), request.sagaId(), null, null, request.reason())));
        };
    }

    private PaymentCancelResponse cancelPartial(String orderNo, CancelPaymentRequest request) {
        Payment payment = paymentTx.getByOrderNo(orderNo);
        return toResponse(cancelExecutor.execute(paymentTx.beginCancel(payment.getId(), request.sagaId(),
                request.orderItemId(), request.amount(), request.reason())));
    }

    private static PaymentCancelResponse toResponse(Result result) {
        return switch (result.status()) {
            case SUCCEEDED -> result.response();
            case PENDING -> throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_PENDING);
            case FAILED -> throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "포트원 취소 요청이 거절되었습니다.");
        };
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
        refundInBackground(payment, portOnePayment, MISMATCH_REASON);
    }

    /**
     * 성립하면 안 되는 결제 환불 — FAILED 확정 + 선기록은 반드시 남기고(실패하면 예외로 호출자에게), 포트원 환불 결과는 기다리지 않는다.
     * 환불이 미확정 · 거절이어도 PENDING / FAILED 기록이 남아 복구 스케줄러 · 경보로 이어진다.
     */
    private void refundInBackground(Payment payment, PortOnePayment portOnePayment, String reason) {
        Begun begun = paymentTx.beginRefund(payment.getId(), null, portOnePayment.cancellableAmount(), reason);
        if (!begun.created()) {
            return;   // 이미 진행 중인 환불 — 웹훅 · 확인 요청 중복
        }
        Result result = cancelExecutor.execute(begun.cancel());
        if (result.status() != CancelStatus.SUCCEEDED) {
            log.error("자동 환불 미완료 — 복구 스케줄러가 이어서 처리 orderNo={}, cancelId={}, status={}, cause={}",
                    payment.getOrderNo(), begun.cancel().cancelId(), result.status(), result.error());
        }
    }
}
