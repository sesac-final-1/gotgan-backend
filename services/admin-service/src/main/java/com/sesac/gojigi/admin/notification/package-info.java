/**
 * notification 도메인 — 실시간 알림 센터 (NTF-08, 10/27 회의 추가 기능)
 * <p>업무 이벤트(SQS: 결제 · 배송 · 취소/반품 · 정산 지급 · 입점 결과)와 다른 서비스 요청(/internal/notifications — 채팅 · 신고 처리 · 시스템 알림)을
 * 받는 쪽별 알림으로 바꿔 알림함(DynamoDB sesac-gojigi-inbox)에 쌓고, Redis Pub/Sub 로 모든 파드에 알려 연결된 화면에 SSE 로 바로 보낸다.
 * <p>받는 쪽: USER#{userId} · STORE#{storeId}(판매자는 스토어 단위) · ADMIN(관리자 전원). 하위 패키지: controller · service · repository · realtime · event · dto
 */
package com.sesac.gojigi.admin.notification;
