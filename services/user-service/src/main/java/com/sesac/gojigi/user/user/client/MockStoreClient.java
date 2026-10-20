package com.sesac.gojigi.user.user.client;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TODO(store 개발 완료 시 삭제): store-service 내부 API 가 없어 storedb 기준으로 흉내 낸다.
 * - 기본은 ACTIVE — storedb 시드 스토어(1 · 2)가 ACTIVE 라 판매자는 탈퇴할 수 없다 (안전한 쪽)
 * - app.store.mock-closed-store-ids 에 적은 스토어만 CLOSED — 폐점 후 탈퇴 흐름 확인용
 */
@Component
public class MockStoreClient implements StoreClient {

    private final Set<Long> closedStoreIds;

    public MockStoreClient(@Value("${app.store.mock-closed-store-ids:}") List<Long> closedStoreIds) {
        this.closedStoreIds = Set.copyOf(closedStoreIds);
    }

    @Override
    public StoreStatus getStoreStatus(Long storeId) {
        return closedStoreIds.contains(storeId) ? StoreStatus.CLOSED : StoreStatus.ACTIVE;
    }
}
