-- orderdb V4 — 상품주문 초기 상태 ORDERED(주문완료) (담당 이경석, 10/23)
-- 상품주문이 만들어질 때부터 PAYMENT_DONE 이면 결제 대기 · 실패 주문까지 "결제완료"로 보이고 판매자 발주확인 대상에 섞인다
-- → 주문 생성 ~ 결제 검증 전은 ORDERED, 결제 검증 성공(0원 주문 포함) 때 주문 PAID 와 함께 PAYMENT_DONE (스펙 ORD-06 · 상품주문 상태표)
-- 주문이 FAILED 로 끝나면 상품주문은 ORDERED 그대로. status 는 VARCHAR 라 기본값 · 주석만 바꾼다

ALTER TABLE order_item
  MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ORDERED'
      COMMENT 'ORDERED / PAYMENT_DONE / PREPARING / SHIPPING / DELIVERED / CONFIRMED / CANCEL_REQUESTED / CANCELLED / RETURN_REQUESTED / RETURNED / EXCHANGE_REQUESTED';
