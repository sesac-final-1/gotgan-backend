package com.sesac.gojigi.payment.payment.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.payment.payment.client.PortOneClientAdapter;
import com.sesac.gojigi.payment.payment.client.PortOneRejectedException;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.dto.response.PaymentCancelResponse;
import com.sesac.gojigi.payment.payment.entity.CancelStatus;
import com.sesac.gojigi.payment.payment.service.PaymentTxService.CancelRef;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 선기록된(PENDING) 취소 1건을 포트원에 보내고 결과를 확정한다 — 모든 단계가 실패할 수 있다고 보고 짠다.
 *
 * <pre>
 * 포트원 응답                         → 처리
 * SUCCEEDED                           → SUCCEEDED 확정 (결제 누적 취소액 반영)
 * REQUESTED (PG 비동기 처리)          → PENDING 유지 + 취소 내역 id 기억 → 웹훅 · 복구 스케줄러가 확정
 * FAILED                              → FAILED
 * 4xx 중 "이미 됐을 수 있음"          → 포트원 조회로 대조 (PAYMENT_ALREADY_CANCELLED · CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN)
 * 그 밖의 4xx                         → FAILED (포트원이 요청 자체를 거절 — 돈이 움직이지 않음)
 * 타임아웃 · 5xx(PG_PROVIDER 포함) · 서킷 OPEN · 응답 해석 실패 → 포트원 조회로 대조
 * 대조도 실패                          → PENDING 유지 → 복구 스케줄러
 * </pre>
 *
 * 대조: 포트원 결제 조회의 cancellations 중 "다른 취소 행에 붙지 않은 것" 가운데 금액이 같고 선기록 이후 요청된 것을 찾는다.
 * 한 결제에 PENDING 은 1건만 허용하므로(beginCancel) 후보는 내 취소뿐이다.
 * 대조했는데 포트원에 흔적이 없고 잔액도 선기록 때와 같으면 → 포트원에 닿지 않은 것이므로 다시 보내도 안전하다(resume).
 * 다시 보낸 요청과 늦게 도착한 첫 요청이 겹쳐도 currentCancellableAmount 때문에 하나만 성공한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelExecutor {

    /** 포트원 requestedAt 과 우리 created_at 의 시계 차이 허용 */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PortOneClientAdapter portOne;
    private final PaymentTxService paymentTx;

    /** 취소 1건의 확정 결과 — SUCCEEDED 일 때만 response 가 있다 */
    public record Result(CancelStatus status, PaymentCancelResponse response, String error) {

        static Result pending(String error) {
            return new Result(CancelStatus.PENDING, null, error);
        }

        static Result failed(String error) {
            return new Result(CancelStatus.FAILED, null, error);
        }
    }

    /** 대조 결과 — notFound: 포트원에 내 취소 흔적이 없음, portoneCancellable: 조회 시점 포트원 잔액(조회 실패면 null) */
    private record Reconciled(Result result, boolean notFound, Long portoneCancellable) {
    }

    /** 방금 선기록한 취소를 포트원에 보낸다 */
    public Result execute(CancelRef cancel) {
        PortOneCancelResponse response;
        try {
            response = portOne.cancel(cancel.portonePaymentId(),
                    PortOneCancelRequest.of(cancel.amount(), cancel.reason(), cancel.cancellableBefore()));
        } catch (PortOneRejectedException e) {
            if (e.maybeAlreadyDone()) {
                return reconcile(cancel, "포트원 거절 " + e.getPortoneType()).result();
            }
            log.warn("포트원 취소 확정 거절 - cancelId={}, type={}", cancel.cancelId(), e.getPortoneType());
            return fail(cancel, "포트원 거절 " + e.getPortoneType());
        } catch (BusinessException e) {
            return reconcile(cancel, "포트원 취소 응답 없음 - " + e.getMessage()).result();
        } catch (RuntimeException e) {
            log.error("포트원 취소 응답 처리 실패 - cancelId={}", cancel.cancelId(), e);
            return reconcile(cancel, "포트원 취소 응답 처리 실패 - " + e.getClass().getSimpleName()).result();
        }
        PortOneCancelResponse.Cancellation c = response == null ? null : response.cancellation();
        if (c == null || c.status() == null) {
            return reconcile(cancel, "포트원 취소 응답에 결과 없음").result();
        }
        return apply(cancel, c.status(), c.id(), c.cancelledAt(), "포트원 취소 응답 " + c.status());
    }

    /**
     * 결과를 모르는 PENDING 을 마무리 — 같은 sagaId 재요청 · 복구 스케줄러가 부른다.
     * 포트원에 흔적이 없고 잔액이 선기록 때와 같으면 그때 처음 보내는 것처럼 다시 보낸다.
     */
    public Result resume(CancelRef cancel) {
        return resume(cancel, true);
    }

    /** 웹훅용 — 대조만 하고 다시 보내지 않는다 (웹훅은 결과 알림일 뿐, 첫 요청이 아직 포트원에서 처리 중일 수 있음) */
    public Result reconcileOnly(CancelRef cancel) {
        return resume(cancel, false);
    }

    private Result resume(CancelRef cancel, boolean allowResend) {
        Reconciled r = reconcile(cancel, "복구 대조");
        if (!allowResend || r.result().status() != CancelStatus.PENDING || !r.notFound() || cancel.portoneCancellationId() != null) {
            return r.result();
        }
        if (r.portoneCancellable() != null && r.portoneCancellable() == cancel.cancellableBefore()) {
            log.info("포트원에 닿지 않은 취소 재요청 - cancelId={}, amount={}", cancel.cancelId(), cancel.amount());
            return execute(cancel);
        }
        if (r.portoneCancellable() != null) {
            // 내 취소 흔적은 없는데 잔액이 바뀜 — 포트원 콘솔에서 직접 취소했거나 기록 밖의 취소. 자동으로 다시 보내지 않는다
            log.error("포트원 잔액이 선기록과 다름 — 수동 확인 필요 cancelId={}, expected={}, portone={}",
                    cancel.cancelId(), cancel.cancellableBefore(), r.portoneCancellable());
            paymentTx.keepPending(cancel.cancelId(), "포트원 잔액 불일치(기대 " + cancel.cancellableBefore()
                    + ", 실제 " + r.portoneCancellable() + ") — 수동 확인");
        }
        return r.result();
    }

    private Reconciled reconcile(CancelRef cancel, String why) {
        Optional<PortOnePayment> found;
        try {
            found = portOne.getPayment(cancel.portonePaymentId());
        } catch (RuntimeException e) {
            log.warn("취소 대조 조회 실패 — PENDING 유지 cancelId={}, why={}, cause={}", cancel.cancelId(), why, e.toString());
            return new Reconciled(keep(cancel, why + " / 대조 조회 실패"), false, null);
        }
        if (found.isEmpty()) {
            log.error("취소 대조 — 포트원에 결제가 없음 cancelId={}, paymentId={}", cancel.cancelId(), cancel.portonePaymentId());
            return new Reconciled(keep(cancel, why + " / 포트원에 결제 없음"), true, null);
        }
        PortOnePayment payment = found.get();
        Optional<PortOnePayment.Cancellation> match = findMine(cancel, payment.cancellationsOrEmpty());
        if (match.isEmpty()) {
            return new Reconciled(keep(cancel, why + " / 포트원에 취소 흔적 없음"), true, payment.cancellableAmount());
        }
        PortOnePayment.Cancellation c = match.get();
        log.info("취소 대조 일치 - cancelId={}, portoneCancellationId={}, status={}", cancel.cancelId(), c.id(), c.status());
        return new Reconciled(apply(cancel, c.status(), c.id(), c.cancelledAt(), why + " / 대조 " + c.status()), false,
                payment.cancellableAmount());
    }

    /** 다른 취소 행에 붙지 않은 포트원 취소 내역 중 내 것 — 완료 > 처리 중 > 실패 순으로 고른다 */
    private Optional<PortOnePayment.Cancellation> findMine(CancelRef cancel, List<PortOnePayment.Cancellation> cancellations) {
        if (cancel.portoneCancellationId() != null) {
            return cancellations.stream().filter(c -> cancel.portoneCancellationId().equals(c.id())).findFirst();
        }
        List<String> taken = paymentTx.recordedCancellationIds(cancel.paymentId(), cancel.cancelId());
        OffsetDateTime notBefore = cancel.createdAt() == null ? null
                : cancel.createdAt().atZone(KST).toOffsetDateTime().minus(CLOCK_SKEW);
        return cancellations.stream()
                .filter(c -> c.id() != null && !taken.contains(c.id()))
                .filter(c -> c.totalAmount() == cancel.amount())
                .filter(c -> notBefore == null || c.requestedAt() == null || !c.requestedAt().isBefore(notBefore))
                .min(Comparator.comparingInt(c -> rank(c.status())));
    }

    private static int rank(String status) {
        return switch (status == null ? "" : status) {
            case "SUCCEEDED" -> 0;
            case "REQUESTED" -> 1;
            default -> 2;
        };
    }

    private Result apply(CancelRef cancel, String status, String portoneCancellationId, OffsetDateTime cancelledAt,
            String why) {
        return switch (status) {
            case "SUCCEEDED" -> new Result(CancelStatus.SUCCEEDED,
                    paymentTx.completeCancel(cancel.cancelId(), portoneCancellationId, cancelledAt), null);
            case "REQUESTED" -> {
                paymentTx.markRequested(cancel.cancelId(), portoneCancellationId);
                yield Result.pending(why);
            }
            case "FAILED" -> fail(cancel, why);
            default -> keep(cancel, why + " / 모르는 상태 " + status);
        };
    }

    private Result keep(CancelRef cancel, String error) {
        paymentTx.keepPending(cancel.cancelId(), error);
        return Result.pending(error);
    }

    private Result fail(CancelRef cancel, String error) {
        paymentTx.failCancel(cancel.cancelId(), error);
        return Result.failed(error);
    }
}
