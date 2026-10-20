package com.sesac.gojigi.user.user.client;

/**
 * store-service 조회 — 판매자 탈퇴 전 스토어 폐점 여부 확인용.
 * TODO(store 개발 완료 시): store-service 내부 API(예: GET /internal/stores/{storeId})가 생기면
 * @HttpExchange 클라이언트 + 어댑터(서킷 브레이커 "store", 장애 시 탈퇴 차단)로 바꾸고 MockStoreClient 를 지운다.
 */
public interface StoreClient {

    StoreStatus getStoreStatus(Long storeId);
}
