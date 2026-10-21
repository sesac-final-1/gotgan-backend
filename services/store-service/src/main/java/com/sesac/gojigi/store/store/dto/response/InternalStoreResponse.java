package com.sesac.gojigi.store.store.dto.response;

import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreStatus;

/**
 * 내부 스토어 상세 — user-service(판매자 탈퇴 전 폐점 확인) · admin-service 가 호출.
 * userId 로 호출자가 "이 스토어가 그 판매자 것인지" 확인할 수 있다. 필드는 추가만 한다.
 */
public record InternalStoreResponse(Long storeId, Long userId, String storeName, StoreStatus status) {

    public static InternalStoreResponse from(Store store) {
        return new InternalStoreResponse(store.getId(), store.getUserId(), store.getStoreName(), store.getStatus());
    }
}
