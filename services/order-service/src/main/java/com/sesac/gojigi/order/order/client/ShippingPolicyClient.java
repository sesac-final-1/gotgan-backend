package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;

/**
 * store-service 배송비 정책 조회.
 * TODO(store shipping-policy 구현 시): GET /internal/stores/{id}/shipping-policy 를 StoreClient 에 추가하고 MockShippingPolicyClient 를 지운다.
 * 계약(응답 모양)은 아직 없음 — store 담당과 확정 필요.
 */
public interface ShippingPolicyClient {

    ShippingPolicy getShippingPolicy(Long storeId);
}
