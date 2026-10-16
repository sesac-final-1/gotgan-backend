package com.sesac.gojigi.payment.payment.service;

import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.dto.request.PreparePaymentRequest;
import com.sesac.gojigi.payment.payment.dto.response.PaymentCancelResponse;
import com.sesac.gojigi.payment.payment.dto.response.PaymentResponse;
import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import com.sesac.gojigi.payment.payment.entity.PaymentStatus;
import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import com.sesac.gojigi.payment.payment.exception.PaymentException;
import com.sesac.gojigi.payment.payment.repository.PaymentCancelRepository;
import com.sesac.gojigi.payment.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment DB 쓰기를 짧은 트랜잭션으로 모은 곳 — 포트원 호출은 이 클래스 밖(PaymentService)에서 한다 (가이드라인 07장: 원격 호출은 트랜잭션 밖).
 * 응답 DTO 는 트랜잭션 안에서 만든다 (PaymentCancel.payment 지연 로딩).
 */
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;

    public record Prepared(PaymentResponse payment, boolean created) {
    }

    /** 같은 orderNo · 같은 금액이면 기존 행, 금액이 다르면 PAYMENT_ORDER_CONFLICT */
    @Transactional
    public Prepared prepare(PreparePaymentRequest request) {
        Optional<Payment> existing = paymentRepository.findByOrderNo(request.orderNo());
        if (existing.isPresent()) {
            if (existing.get().getAmount() != request.amount()) {
                throw new PaymentException(PaymentErrorCode.PAYMENT_ORDER_CONFLICT);
            }
            return new Prepared(PaymentResponse.from(existing.get()), false);
        }
        Payment saved = paymentRepository.saveAndFlush(Payment.ready(request.orderId(), request.orderNo(), request.amount()));
        return new Prepared(PaymentResponse.from(saved), true);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderNo(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo);
    }

    @Transactional(readOnly = true)
    public Payment getByOrderNo(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByPortonePaymentId(String portonePaymentId) {
        return paymentRepository.findByPortonePaymentId(portonePaymentId);
    }

    /** 같은 sagaId 로 이미 취소했으면 그 결과 */
    @Transactional(readOnly = true)
    public Optional<PaymentCancelResponse> findCancelBySagaId(String sagaId) {
        return paymentCancelRepository.findBySagaId(sagaId).map(c -> PaymentCancelResponse.of(c.getPayment(), c));
    }

    /** 포트원 PAID + 금액 일치 확인 후 — 이미 PAID 면 그대로 (확인 요청 · 웹훅 중복) */
    @Transactional
    public PaymentResponse markPaid(Long paymentId, PortOnePayment portOne) {
        Payment payment = load(paymentId);
        if (!payment.isPaid()) {
            payment.markPaid(portOne.pgTxId(), portOne.methodCode(), toKst(portOne.paidAt()));
        }
        return PaymentResponse.from(payment);
    }

    /** 포트원에 결제가 없거나 미결제 — READY 만 FAILED 로 */
    @Transactional
    public PaymentCancelResponse markFailed(Long paymentId) {
        Payment payment = load(paymentId);
        if (payment.getStatus() == PaymentStatus.READY) {
            payment.markFailed();
        }
        return PaymentCancelResponse.of(payment, null);
    }

    /**
     * 결제가 성립하면 안 되는데 포트원에 돈이 들어온 경우 — 포트원 취소 성공 후 기록하고 FAILED.
     * 금액 불일치 자동 취소(sagaId 없음) · READY 보상 중 발견 · 만료 후 늦게 들어온 결제
     */
    @Transactional
    public PaymentCancelResponse recordRefundAndFail(Long paymentId, String sagaId, long cancelAmount, String reason,
            PortOneCancelResponse.Cancellation cancellation) {
        Payment payment = load(paymentId);
        if (payment.getStatus() == PaymentStatus.READY) {
            payment.markFailed();
        }
        PaymentCancel cancel = paymentCancelRepository.save(PaymentCancel.of(payment, null, sagaId, cancelAmount, reason,
                cancellation.id(), cancelledAt(cancellation)));
        return PaymentCancelResponse.of(payment, cancel);
    }

    /** 결제 완료 건 취소 — 누적 취소액 반영(PARTIAL_CANCELLED / CANCELLED) + 취소 기록 */
    @Transactional
    public PaymentCancelResponse recordCancel(Long paymentId, String sagaId, Long orderItemId, long cancelAmount,
            String reason, PortOneCancelResponse.Cancellation cancellation) {
        Payment payment = load(paymentId);
        LocalDateTime at = cancelledAt(cancellation);
        payment.applyCancel(cancelAmount, at);
        PaymentCancel cancel = paymentCancelRepository.save(
                PaymentCancel.of(payment, orderItemId, sagaId, cancelAmount, reason, cancellation.id(), at));
        return PaymentCancelResponse.of(payment, cancel);
    }

    private Payment load(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private static LocalDateTime cancelledAt(PortOneCancelResponse.Cancellation cancellation) {
        return cancellation.cancelledAt() == null ? LocalDateTime.now(KST) : toKst(cancellation.cancelledAt());
    }

    private static LocalDateTime toKst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toLocalDateTime();
    }
}
