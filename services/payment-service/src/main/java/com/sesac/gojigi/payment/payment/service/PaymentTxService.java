package com.sesac.gojigi.payment.payment.service;

import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.dto.request.PreparePaymentRequest;
import com.sesac.gojigi.payment.payment.dto.response.PaymentCancelResponse;
import com.sesac.gojigi.payment.payment.dto.response.PaymentResponse;
import com.sesac.gojigi.payment.payment.entity.CancelStatus;
import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import com.sesac.gojigi.payment.payment.entity.PaymentStatus;
import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import com.sesac.gojigi.payment.payment.exception.PaymentException;
import com.sesac.gojigi.payment.payment.repository.PaymentCancelRepository;
import com.sesac.gojigi.payment.payment.repository.PaymentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment DB 쓰기를 짧은 트랜잭션으로 모은 곳 — 포트원 호출은 이 클래스 밖(PaymentService · PaymentCancelExecutor)에서 한다
 * (가이드라인 07장: 원격 호출은 트랜잭션 밖). 응답 DTO 는 트랜잭션 안에서 만든다 (PaymentCancel.payment 지연 로딩).
 *
 * 취소는 "선기록 → 포트원 → 확정" 3단계 (V2):
 * beginCancel / beginRefund 가 PENDING 행을 남기고, 포트원 결과에 따라 completeCancel / keepPending / markRequested / failCancel.
 * 어느 단계에서 죽어도 PENDING 이 남아 복구 스케줄러가 포트원 조회로 마무리한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;

    public record Prepared(PaymentResponse payment, boolean created) {
    }

    /** 포트원 호출 · 대조에 필요한 취소 1건의 값 — 트랜잭션 밖으로 엔티티를 넘기지 않으려고 꺼내 둔다 */
    public record CancelRef(Long cancelId, Long paymentId, String portonePaymentId, String sagaId, CancelStatus status,
            long amount, long cancellableBefore, String reason, String portoneCancellationId, int attemptCount,
            LocalDateTime createdAt) {

        static CancelRef of(PaymentCancel c) {
            Payment p = c.getPayment();
            return new CancelRef(c.getId(), p.getId(), p.getPortonePaymentId(), c.getSagaId(), c.getStatus(),
                    c.getCancelAmount(), c.getCancellableBefore() == null ? 0 : c.getCancellableBefore(), c.getReason(),
                    c.getPortoneCancellationId(), c.getAttemptCount(), c.getCreatedAt());
        }
    }

    /** beginRefund 결과 — created=false 면 이미 진행 중인 환불이 있어 새로 포트원을 부를 필요가 없다 */
    public record Begun(CancelRef cancel, boolean created) {
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

    // ── 취소 1단계: 선기록

    /**
     * 결제 완료 건 취소 시작 — 결제 행을 잠그고(같은 결제의 취소를 한 줄로) PENDING 을 남긴다. amount 가 null 이면 남은 금액 전부.
     * 다른 취소가 아직 PENDING 이면 PAYMENT_CANCEL_PENDING — 잔액 기준이 겹치면 포트원이 거절하고, 대조 때 남의 취소를 내 것으로 오인할 수 있다.
     * 같은 sagaId 동시 요청은 uk_payment_cancel_saga_id 위반으로 여기서 실패한다 (호출 쪽이 다시 조회).
     */
    @Transactional
    public CancelRef beginCancel(Long paymentId, String sagaId, Long orderItemId, Long amount, String reason) {
        Payment payment = lock(paymentId);
        if (!payment.getStatus().isCancellable()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INVALID_STATUS);
        }
        if (paymentCancelRepository.findFirstByPaymentIdAndStatus(paymentId, CancelStatus.PENDING).isPresent()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_PENDING);
        }
        long remaining = payment.remainingAmount();
        long cancelAmount = amount == null ? remaining : amount;
        if (cancelAmount < 1 || cancelAmount > remaining) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED);
        }
        PaymentCancel saved = paymentCancelRepository.saveAndFlush(
                PaymentCancel.pending(payment, orderItemId, sagaId, cancelAmount, remaining, reason));
        return CancelRef.of(saved);
    }

    /**
     * 성립하면 안 되는 결제의 환불 시작 — 금액 불일치 · READY 보상 중 발견 · 결제 실패 처리 후 늦게 들어온 결제.
     * READY 면 같은 트랜잭션에서 FAILED 로 확정한다(이 결제는 주문에 쓰지 않는다는 결정이 먼저 남도록). 금액은 포트원 잔액 그대로.
     * 이미 진행 중인 환불이 있으면 새로 만들지 않고 그것을 돌려준다(웹훅 재전송 · 확인 요청 중복에도 환불 1번).
     */
    @Transactional
    public Begun beginRefund(Long paymentId, String sagaId, long portoneCancellable, String reason) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() == PaymentStatus.READY) {
            payment.markFailed();
        }
        Optional<PaymentCancel> inFlight = paymentCancelRepository.findFirstByPaymentIdAndStatus(paymentId, CancelStatus.PENDING);
        if (inFlight.isPresent()) {
            if (sagaId == null || sagaId.equals(inFlight.get().getSagaId()) || inFlight.get().getSagaId() == null) {
                return new Begun(CancelRef.of(inFlight.get()), false);
            }
            throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_PENDING);
        }
        PaymentCancel saved = paymentCancelRepository.saveAndFlush(
                PaymentCancel.pending(payment, null, sagaId, portoneCancellable, portoneCancellable, reason));
        return new Begun(CancelRef.of(saved), true);
    }

    /** FAILED 를 같은 sagaId 로 다시 — 포트원 최신 잔액으로 PENDING 부터 */
    @Transactional
    public CancelRef retryFailed(Long cancelId, long portoneCancellable) {
        PaymentCancel cancel = loadCancel(cancelId);
        lock(cancel.getPayment().getId());
        if (paymentCancelRepository.findFirstByPaymentIdAndStatus(cancel.getPayment().getId(), CancelStatus.PENDING).isPresent()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_PENDING);
        }
        cancel.retry(portoneCancellable);
        return CancelRef.of(cancel);
    }

    // ── 취소 3단계: 확정

    /** 포트원 취소 완료 확인 — 결제 완료 건이면 누적 취소액 반영(PARTIAL_CANCELLED / CANCELLED). 이미 확정됐으면 그대로 */
    @Transactional
    public PaymentCancelResponse completeCancel(Long cancelId, String portoneCancellationId, OffsetDateTime cancelledAt) {
        PaymentCancel cancel = loadCancel(cancelId);
        Payment payment = cancel.getPayment();
        if (cancel.isPending()) {
            LocalDateTime at = cancelledAt == null ? LocalDateTime.now(KST) : toKst(cancelledAt);
            cancel.countAttempt();
            cancel.succeed(portoneCancellationId, at);
            if (payment.getStatus().isCancellable()) {
                payment.applyCancel(cancel.getCancelAmount(), at);
            }
        }
        return PaymentCancelResponse.of(payment, cancel);
    }

    /** 포트원이 받았지만 처리 중(REQUESTED) — PENDING 유지, 취소 내역 id 기억 */
    @Transactional
    public void markRequested(Long cancelId, String portoneCancellationId) {
        PaymentCancel cancel = loadCancel(cancelId);
        if (cancel.isPending()) {
            cancel.countAttempt();
            cancel.markRequested(portoneCancellationId);
        }
    }

    /** 결과 미확정 — PENDING 유지, 사유 · 시도 횟수만 갱신 (updated_at 이 바뀌어 복구 스케줄러 대기 시간이 다시 시작됨) */
    @Transactional
    public void keepPending(Long cancelId, String error) {
        PaymentCancel cancel = loadCancel(cancelId);
        if (cancel.isPending()) {
            cancel.countAttempt();
            cancel.keepPending(error);
        }
    }

    /** 포트원이 확정적으로 거절 — FAILED */
    @Transactional
    public void failCancel(Long cancelId, String error) {
        PaymentCancel cancel = loadCancel(cancelId);
        if (cancel.isPending()) {
            cancel.countAttempt();
            cancel.fail(error);
        }
    }

    /** 거절된 자동 환불을 재시도조차 못 함 — 시도 횟수만 올림 */
    @Transactional
    public void failCancelAttempt(Long cancelId, String error) {
        PaymentCancel cancel = loadCancel(cancelId);
        if (cancel.getStatus() == CancelStatus.FAILED) {
            cancel.recordFailedAttempt(error);
        }
    }

    // ── 조회

    @Transactional(readOnly = true)
    public Optional<CancelRef> findCancelBySagaId(String sagaId) {
        return paymentCancelRepository.findBySagaId(sagaId).map(CancelRef::of);
    }

    @Transactional(readOnly = true)
    public PaymentCancelResponse cancelResponse(Long cancelId) {
        PaymentCancel cancel = loadCancel(cancelId);
        return PaymentCancelResponse.of(cancel.getPayment(), cancel);
    }

    @Transactional(readOnly = true)
    public List<CancelRef> pendingCancelsOf(Long paymentId) {
        return paymentCancelRepository.findByPaymentIdAndStatus(paymentId, CancelStatus.PENDING).stream()
                .map(CancelRef::of).toList();
    }

    /** 복구 스케줄러 — staleAfter 동안 확정되지 않은 PENDING */
    @Transactional(readOnly = true)
    public List<CancelRef> stalePendingCancels(Duration staleAfter, int limit) {
        return paymentCancelRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(CancelStatus.PENDING,
                LocalDateTime.now(KST).minus(staleAfter), Limit.of(limit)).stream().map(CancelRef::of).toList();
    }

    /** 복구 스케줄러 — 포트원이 거절한 자동 환불 중 재시도 횟수가 남은 것 */
    @Transactional(readOnly = true)
    public List<CancelRef> retryableFailedRefunds(Duration staleAfter, int maxAttempts, int limit) {
        return paymentCancelRepository.findByStatusAndSagaIdIsNullAndAttemptCountLessThanAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                CancelStatus.FAILED, maxAttempts, LocalDateTime.now(KST).minus(staleAfter), Limit.of(limit)).stream()
                .map(CancelRef::of).toList();
    }

    /** 이 결제에서 다른 취소 행에 이미 붙은 포트원 취소 내역 id */
    @Transactional(readOnly = true)
    public List<String> recordedCancellationIds(Long paymentId, Long exceptCancelId) {
        return paymentCancelRepository.findRecordedCancellationIds(paymentId, exceptCancelId).stream()
                .filter(Objects::nonNull).toList();
    }

    private Payment load(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private Payment lock(Long paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private PaymentCancel loadCancel(Long cancelId) {
        return paymentCancelRepository.findById(cancelId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private static LocalDateTime toKst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toLocalDateTime();
    }
}
