-- orderdb V5 — 클레임 환원 포인트 · 사유 ETC (담당 이경석, 10/23)
-- 취소 · 반품 환불은 현금(refund_amt)과 포인트(restore_point)로 나뉜다: 상품주문 사용 포인트는 포인트로 돌려주고,
-- 스토어 마지막 상품주문 취소의 배송비가 포인트로 결제됐던 몫도 포인트로 돌려준다. 클레임을 만들 때 계산해 두어야
-- 환불 Saga 를 재시도해도 같은 값으로 user 를 부른다 (OrderItemCancelled · Returned.restoredPoint).
-- reason_code 는 VARCHAR 라 ETC 추가는 주석만.

ALTER TABLE order_claim
  MODIFY COLUMN reason_code VARCHAR(30) NOT NULL
      COMMENT 'CHANGE_OF_MIND / DEFECTIVE / WRONG_DELIVERY / DELAYED / ETC',
  ADD COLUMN restore_point BIGINT NULL
      COMMENT '돌려줄 사용 포인트 (원) — 교환이면 NULL (OrderItemCancelled · Returned.restoredPoint)' AFTER refund_amt;
