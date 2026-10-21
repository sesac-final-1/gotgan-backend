-- orderdb V3 — Saga 재시도 (담당 이경석, 10/21)
-- 결제 취소 409 PAYMENT_CANCEL_PENDING · order 쪽 타임아웃 · 5xx 는 "결과 모름" → 같은 saga_id 로 스케줄러가 재시도한다 (docs/api/payment.md 7 · 8절)
-- 간격 1 · 1 · 2분 후 5분 상한 → 1 · 2 · 4 · 9 · 14 · 19 · 24 · 29분, 최대 8회(약 30분). 넘으면 FAILED + ERROR 로그(수동 확인)
-- current_step 은 VARCHAR 라 값 추가는 DDL 이 필요 없고, 주석만 취소 · 반품 Saga 단계로 넓힌다

ALTER TABLE saga_instance
  MODIFY COLUMN current_step VARCHAR(30) NOT NULL
      COMMENT 'PLACE_ORDER: ORDER_CREATED / POINT_USED / STOCK_RESERVED / PAYMENT_READY / PAYMENT_VERIFIED / DONE, CANCEL_ITEM · RETURN_ITEM: PAYMENT_CANCEL_REQUESTED / PAYMENT_CANCELLED / STOCK_RESTORED / POINT_RESTORED / DONE',
  ADD COLUMN attempt_count INT NOT NULL DEFAULT 0
      COMMENT '현재 단계 재시도 횟수 — 단계가 넘어가면 0, 8회 넘으면 FAILED' AFTER current_step,
  ADD COLUMN next_retry_at DATETIME(6) NULL
      COMMENT '다음 재시도 시각 — NULL 이면 재시도 대상 아님 (복구 스케줄러가 status · next_retry_at 으로 조회)' AFTER status,   -- 컨벤션 3-9: 도메인 시간은 상태 뒤
  ADD INDEX idx_saga_instance_status_next_retry (status, next_retry_at);   -- 재시도 스케줄러
