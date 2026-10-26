package com.sesac.gojigi.order.saga.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.common.logging.OrderLogContext;
import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.UserClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PointReversalRequest;
import com.sesac.gojigi.order.order.client.dto.StockRestoreRequest;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.service.CancelItemTxService.CancelContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결제완료 단계 즉시 취소 Saga (스펙 CLM-01 · 가이드라인 06장 "결제 후 취소 · 반품 Saga").
 * 접수(클레임 자동 승인 · 상품주문 CANCEL_REQUESTED) → ① payment 부분 취소 → ② product 재고 복원 → ③ user 포인트 환원 · 회수 → 취소 완료.
 * 앞으로만 간다 — 결제를 이미 돌려준 뒤라 되돌리지 않고, 실패한 단계에서 멈춰 같은 claimId · sagaId 로 다시 시도한다.
 * 결제 취소가 실패하면(409 PAYMENT_CANCEL_PENDING · 장애) 재고 · 포인트를 건드리지 않고 클레임 APPROVED 로 남는다 — SagaRecoveryScheduler 가 재시도.
 *
 * ⚠ @Transactional 을 붙이지 않는다 — DB 변경은 CancelItemTxService · OrderSagaTxService 의 짧은 트랜잭션으로.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelOrderItemSaga {

    private static final String CANCEL_REASON = "구매자 취소";

    private final CancelItemTxService cancelTx;
    private final OrderSagaTxService sagaTx;
    private final PaymentClientAdapter payment;
    private final ProductClientAdapter product;
    private final UserClientAdapter user;

    /** [요청] POST /api/orders/{orderNo}/claims (type=CANCEL) — 접수 후 바로 끝까지 진행해 보고, 막히면 처리 중으로 응답 */
    public ClaimResponse request(Long buyerId, String orderNo, ClaimCreateRequest request) {
        if (request.type() != ClaimType.CANCEL) {
            throw new ClaimException(ClaimErrorCode.CLAIM_TYPE_NOT_SUPPORTED);   // 반품(CLM-03) · 교환(CLM-04)은 후속
        }
        CancelContext ctx = cancelTx.open(buyerId, orderNo, request.orderItemId(), request.reasonCode(),
                request.reasonDetail());
        OrderLogContext.put(orderNo, ctx.sagaId());
        boolean done = proceed(ctx, SagaStep.PAYMENT_CANCEL_REQUESTED);
        return new ClaimResponse(ctx.claimId(), ctx.orderItemId(), ClaimType.CANCEL,
                done ? ClaimStatus.COMPLETED : ClaimStatus.APPROVED, ctx.refundAmt(), ctx.restorePoint());
    }

    /** 복구 (SagaRecoveryScheduler) — 멈춘 단계부터 다시 */
    public void resume(SagaInstance saga) {
        proceed(cancelTx.load(saga.getId()), saga.getCurrentStep());
    }

    /**
     * from 단계부터 끝까지 — 단계마다 성공하면 기록하고 다음으로. 실패하면 그 단계에 멈춰 재시도 시각을 남긴다(1 · 1 · 2분 후 5분, 8회 넘으면 FAILED).
     * 참여자는 모두 같은 키(sagaId · claimId) 멱등이라, 응답만 못 받은 단계를 다시 불러도 두 번 처리되지 않는다.
     */
    private boolean proceed(CancelContext ctx, SagaStep from) {
        SagaStep step = from;
        try {
            if (step == SagaStep.PAYMENT_CANCEL_REQUESTED) {                                    // ① 결제 부분 취소
                if (ctx.refundAmt() > 0) {   // 전액 포인트 주문이면 돌려줄 현금이 없다
                    payment.cancel(ctx.orderNo(), PaymentCancelRequest.partial(ctx.sagaId(), ctx.orderItemId(),
                            ctx.refundAmt(), CANCEL_REASON));
                }
                step = advance(ctx, SagaStep.PAYMENT_CANCELLED);
            }
            if (step == SagaStep.PAYMENT_CANCELLED) {                                           // ② 재고 복원
                product.restore(new StockRestoreRequest(ctx.claimId(), ctx.productOptionId(), ctx.quantity()));
                step = advance(ctx, SagaStep.STOCK_RESTORED);
            }
            if (step == SagaStep.STOCK_RESTORED) {                                              // ③ 포인트 환원 · 회수
                user.reversePoints(new PointReversalRequest(ctx.claimId(), ctx.orderItemId(), ctx.buyerId(),
                        ctx.orderNo(), ctx.restorePoint(), ctx.earnedPoint()));
                step = advance(ctx, SagaStep.POINT_RESTORED);
            }
            if (step == SagaStep.POINT_RESTORED) {
                cancelTx.finish(ctx.sagaInstanceId());   // 상품주문 CANCELLED · 클레임 COMPLETED
                log.info("상품주문 취소 완료 - orderItemId={}, refundAmt={}, restorePoint={}",
                        ctx.orderItemId(), ctx.refundAmt(), ctx.restorePoint());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("상품주문 취소 진행 멈춤 - 단계={}, 복구 스케줄러가 같은 claimId · sagaId 로 다시 시도, 원인={}", step, reason(e));
            sagaTx.scheduleRetry(ctx.sagaInstanceId(), reason(e));
            return false;
        }
    }

    private SagaStep advance(CancelContext ctx, SagaStep step) {
        sagaTx.advance(ctx.sagaInstanceId(), step);
        return step;
    }

    private static String reason(Throwable t) {
        return t instanceof BusinessException business
                ? business.getErrorCode().getCode() + ": " + business.getMessage()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
