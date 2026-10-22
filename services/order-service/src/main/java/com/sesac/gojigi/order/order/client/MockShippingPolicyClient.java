package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import org.springframework.stereotype.Component;

/**
 * TODO(store shipping-policy 구현 시 삭제): store-service 배송비 정책 API 가 없어 모든 스토어를 같은 정책으로 흉내 낸다.
 * 기본 배송비 3,000원 · 스토어 상품 합계 50,000원 이상 무료 (목업 화면 기준).
 */
@Component
public class MockShippingPolicyClient implements ShippingPolicyClient {

    private static final ShippingPolicy DEFAULT_POLICY = new ShippingPolicy(3_000L, 50_000L);

    @Override
    public ShippingPolicy getShippingPolicy(Long storeId) {
        return DEFAULT_POLICY;
    }
}
