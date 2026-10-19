package com.sesac.gojigi.settlement.common.event;

/**
 * SQS 로 받은 이벤트 1종을 처리한다 — 구현체 이름은 {이벤트}Listener (컨벤션 이름 규칙).
 * 정상 반환 = 메시지 삭제, 예외 = 삭제하지 않아 가시성 타임아웃 뒤 재전달(3회 실패 시 DLQ). 같은 eventId 재전달은 구현체가 processed_event 로 건너뛴다.
 */
public interface SqsEventListener {

    /** 처리할 봉투 eventType (예: StoreApproved) */
    String eventType();

    /** 봉투 JSON 원문 */
    void onMessage(String envelopeJson);
}
