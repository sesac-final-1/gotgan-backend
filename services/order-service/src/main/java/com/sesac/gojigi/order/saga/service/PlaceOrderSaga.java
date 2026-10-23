package com.sesac.gojigi.order.saga.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.common.logging.OrderLogContext;
import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.ShippingPolicyClient;
import com.sesac.gojigi.order.order.client.StoreClientAdapter;
import com.sesac.gojigi.order.order.client.UserClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentPrepareRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.client.dto.PointUseRequest;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.StockItem;
import com.sesac.gojigi.order.order.client.dto.StockReleaseRequest;
import com.sesac.gojigi.order.order.client.dto.StockReserveRequest;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.dto.response.OrderCreateResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.order.service.OrderTxService.PendingOrder;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 주문 생성 Saga 오케스트레이터 [요청 1] POST /api/orders (가이드라인 06장 · 스펙 ORD-04).
 * ① 주문 PENDING + 상품주문 스냅샷 → ② 포인트 사용(user, 쓸 때만) → ③ 재고 예약(product) → ④ 결제 준비(payment) → 결제창 정보 응답.
 * 어느 단계든 실패하면 시도했을 수 있는 단계를 역순으로 보상하고 주문 FAILED — 결제창은 띄우지 않는다.
 *
 * ⚠ 이 클래스에는 @Transactional 을 붙이지 않는다 — 원격 호출을 DB 트랜잭션 안에 넣으면 커넥션을 오래 잡고,
 *    롤백해도 원격 쪽은 되돌아가지 않는다. DB 변경은 OrderTxService · OrderSagaTxService 의 짧은 트랜잭션으로.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceOrderSaga {

    /** 주문번호(그날 순번)가 동시 주문과 겹치면 다시 발급하는 횟수 */
    private static final int ORDER_NO_ATTEMPTS = 3;
    private static final String CANCEL_REASON = "주문 처리 실패";

    private final OrderTxService orderTx;
    private final OrderSagaTxService sagaTx;
    private final UserClientAdapter user;
    private final StoreClientAdapter store;
    private final ShippingPolicyClient shippingPolicy;
    private final ProductClientAdapter product;
    private final PaymentClientAdapter payment;

    public OrderCreateResponse place(Long buyerId, OrderCreateRequest request) {
        // ① 주문 PENDING — 가격 · 배송비 · 포인트 배분은 서버가 다시 계산 (여기서 실패하면 주문도 Saga 도 없음)
        // Mapping Product
        List<OrderPricing.Line> lines = lines(request);
        UserSummaryResponse buyer = user.getUser(buyerId);  // 회원 정보 가져오기
        Map<Long, String> storeNames = storeNames(lines);   // 스토어 정보 가져오기(중복 제거)
        OrderPricing.Result priced = OrderPricing.calculate(lines, shippingPolicy::getShippingPolicy, request.usePoint());
        PendingOrder pending = createPending(buyer, request, priced, storeNames);   // order tx
        // Ready to Saga
        Order order = pending.order();
        SagaInstance saga = sagaTx.startPlaceOrder(order);
        OrderLogContext.put(order.getOrderNo(), saga.getSagaId());  // Saga Log

        List<StockItem> stockItems = request.items().stream()   // Parse request to Product Object
                .map(item -> new StockItem(item.productOptionId(), item.quantity())).toList();
        String orderName = orderName(pending.items());
        SagaStep attempted = SagaStep.ORDER_CREATED;    //Saga status
        // Start Saga
        try {
            if (order.getUsedPoint() > 0) {                                                         // ② 포인트 사용
                attempted = SagaStep.POINT_USED;
                user.usePoints(new PointUseRequest(saga.getSagaId(), buyerId, order.getOrderNo(), order.getUsedPoint()));
                sagaTx.advance(saga.getId(), SagaStep.POINT_USED);
            }
            attempted = SagaStep.STOCK_RESERVED;                                                    // ③ 재고 예약
            product.reserve(new StockReserveRequest(saga.getSagaId(), stockItems));
            sagaTx.advance(saga.getId(), SagaStep.STOCK_RESERVED);

            if (order.getPayAmt() == 0) {
                // 전액 포인트 — 포트원은 0원 결제창이 없어 결제 준비 · 검증을 건너뛰고 바로 PAID (docs/api/payment.md 1절)
                // TODO(결제 확인 PR · ORD-09): OrderPaid 이벤트를 결제 확인과 같은 경로로 발행
                orderTx.markPaid(order.getId());
                sagaTx.complete(saga.getId());
                log.info("주문 완료(전액 포인트) - usedPoint={}", order.getUsedPoint());
                return new OrderCreateResponse(order.getOrderNo(), OrderStatus.PAID, 0L, null, orderName);
            }
            attempted = SagaStep.PAYMENT_READY;                                                     // ④ 결제 준비
            PaymentResponse ready = payment.prepare(new PaymentPrepareRequest(order.getId(), order.getOrderNo(),
                    order.getPayAmt(), saga.getSagaId()));
            sagaTx.advance(saga.getId(), SagaStep.PAYMENT_READY);                                  // 이제 결제 대기 (최대 15분)
            log.info("주문 생성 · 결제 대기 - payAmt={}, usedPoint={}", order.getPayAmt(), order.getUsedPoint());
            return new OrderCreateResponse(order.getOrderNo(), OrderStatus.PENDING, order.getPayAmt(),
                    ready.portonePaymentId(), orderName);
        } catch (RuntimeException e) {
            compensate(saga, order, stockItems, attempted, e);
            throw e;   // ORDER_OUT_OF_STOCK · ORDER_POINT_INSUFFICIENT · EXTERNAL_API_ERROR ... 그대로 응답
        }
    }

    /**
     * 역순 보상 — 결과를 모르는 호출(타임아웃 등)도 있으므로 "시도했을 수 있는" 단계는 전부 되돌린다.
     * 참여자는 모두 "되돌릴 게 없으면 아무 일도 안 함" + sagaId 멱등이라 여러 번 불러도 안전하다.
     * 보상 호출이 실패하면(409 PAYMENT_CANCEL_PENDING · 장애) 주문은 PENDING 으로 두고 Saga 를 재시도 대상으로 남긴다.
     * TODO(ORD-07 SagaRecoveryScheduler): COMPENSATING · next_retry_at 지난 Saga 를 같은 순서로 다시 보상하고 주문 FAILED.
     */
    private void compensate(SagaInstance saga, Order order, List<StockItem> stockItems, SagaStep attempted,
                            RuntimeException cause) {
        String sagaId = saga.getSagaId();
        sagaTx.startCompensating(saga.getId(), reason(cause));
        try {
            if (reached(attempted, SagaStep.PAYMENT_READY)) {
                payment.cancel(order.getOrderNo(), PaymentCancelRequest.full(sagaId, CANCEL_REASON));
            }
            if (reached(attempted, SagaStep.STOCK_RESERVED)) {
                product.release(sagaId, new StockReleaseRequest(stockItems));
            }
            if (reached(attempted, SagaStep.POINT_USED) && order.getUsedPoint() > 0) {
                user.restorePoints(sagaId);
            }
            orderTx.markFailed(order.getId());
            sagaTx.compensated(saga.getId());
            log.info("주문 Saga 보상 완료 - 원인={}", reason(cause));
        } catch (RuntimeException again) {
            log.error("주문 Saga 보상 미완료 - 복구 스케줄러가 같은 sagaId 로 다시 시도, 원인={}", reason(cause), again);
            sagaTx.scheduleRetry(saga.getId(), reason(again));
        }
    }

    /** 요청 옵션을 product 현재 값과 짝짓는다 — 같은 옵션 중복은 400, 없거나 구매 불가인 옵션이 하나라도 있으면 409 */
    private List<OrderPricing.Line> lines(OrderCreateRequest request) {
        List<Long> optionIds = request.items().stream().map(OrderCreateRequest.Item::productOptionId).toList();
        if (optionIds.stream().distinct().count() != optionIds.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "같은 상품 옵션이 두 번 들어 있습니다.");
        }
        Map<Long, ProductOptionResponse> options = product.getOptions(optionIds).stream()
                .collect(Collectors.toMap(ProductOptionResponse::productOptionId, Function.identity()));
        return request.items().stream().map(item -> {
            ProductOptionResponse option = options.get(item.productOptionId());
            if (option == null || !option.purchasable()) {
                throw new OrderException(OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
            }
            return new OrderPricing.Line(option, item.quantity());
        }).toList();
    }

    /** 상품주문 스냅샷의 스토어명 — 스토어마다 한 번 조회 */
    private Map<Long, String> storeNames(List<OrderPricing.Line> lines) {
        Map<Long, String> names = new LinkedHashMap<>();
        lines.forEach(line -> names.computeIfAbsent(line.option().storeId(), id -> store.getStore(id).storeName()));

        return names;
    }

    private PendingOrder createPending(UserSummaryResponse buyer, OrderCreateRequest request, OrderPricing.Result priced,
                                       Map<Long, String> storeNames) {
        for (int attempt = 1; ; attempt++) {
            try {
                return orderTx.createPending(buyer, request, priced, storeNames);
            } catch (DataIntegrityViolationException e) {
                if (attempt >= ORDER_NO_ATTEMPTS) {
                    throw e;
                }
                log.warn("주문번호 충돌 - 다시 발급, attempt={}", attempt);
            }
        }
    }

    /** 결제창 주문명 — "제철 사과 3kg 외 2건" */
    static String orderName(List<OrderItem> items) {
        String first = items.get(0).getProductName();
        return items.size() == 1 ? first : first + " 외 " + (items.size() - 1) + "건";
    }

    private static boolean reached(SagaStep attempted, SagaStep step) {
        return attempted.compareTo(step) >= 0;
    }

    private static String reason(Throwable t) {
        return t instanceof BusinessException business
                ? business.getErrorCode().getCode() + ": " + business.getMessage()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
