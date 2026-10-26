package com.sesac.gojigi.order.order.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.order.dto.request.SellerShipRequest;
import com.sesac.gojigi.order.order.dto.response.OrderItemResponse;
import com.sesac.gojigi.order.order.dto.response.SellerBulkResponse;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 판매자 발주확인 · 송장 등록 · 배송완료 (DLV-01 ~ 03) — 일괄 요청을 상품주문마다 따로 처리해 하나가 실패해도 나머지는 반영한다.
 * 같은 상품주문을 다시 처리하면(중복 등록) 상태 전이에서 걸려 failed 로 돌려준다 — 이미 바뀐 값은 그대로.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerDeliveryService {

    private final DeliveryTxService deliveryTx;

    public SellerBulkResponse prepare(Long storeId, Long sellerId, List<Long> orderItemIds) {
        requireStore(storeId);
        return each(orderItemIds.stream().distinct().toList(), id -> deliveryTx.prepare(storeId, sellerId, id));
    }

    public SellerBulkResponse ship(Long storeId, Long sellerId, List<SellerShipRequest.Item> items) {
        requireStore(storeId);
        Map<Long, SellerShipRequest.Item> byId = new LinkedHashMap<>();   // 같은 상품주문이 두 번이면 처음 것만
        items.forEach(item -> byId.putIfAbsent(item.orderItemId(), item));
        return each(List.copyOf(byId.keySet()), id -> deliveryTx.ship(storeId, sellerId, id,
                byId.get(id).courier(), byId.get(id).trackingNo()));
    }

    public SellerBulkResponse deliver(Long storeId, Long sellerId, List<Long> orderItemIds) {
        requireStore(storeId);
        return each(orderItemIds.stream().distinct().toList(), id -> deliveryTx.deliver(storeId, sellerId, id));
    }

    private SellerBulkResponse each(List<Long> ids, Function<Long, OrderItemResponse> action) {
        List<OrderItemResponse> succeeded = new ArrayList<>();
        List<SellerBulkResponse.Failure> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                succeeded.add(action.apply(id));
            } catch (BusinessException e) {
                failed.add(new SellerBulkResponse.Failure(id, e.getErrorCode().getCode(), e.getMessage()));
            } catch (OptimisticLockingFailureException e) {
                // 판매자 버튼과 자동 배송완료가 동시에 — 먼저 커밋된 쪽이 반영됨
                OrderErrorCode code = OrderErrorCode.ORDER_INVALID_STATUS;
                failed.add(new SellerBulkResponse.Failure(id, code.getCode(), code.getMessage()));
            }
        }
        log.info("판매자 일괄 처리 - 성공 {}건 · 실패 {}건", succeeded.size(), failed.size());
        return new SellerBulkResponse(succeeded, failed);
    }

    /** 판매자인데 스토어가 없음(입점 승인 전 · 토큰 갱신 전) — 게이트웨이가 SELLER 역할은 이미 확인 */
    private static void requireStore(Long storeId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다.");
        }
    }
}
