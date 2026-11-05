-- settlementdb V9 — 아웃박스 payload TEXT(64KB) → MEDIUMTEXT (담당 구지훈, 11/5)
-- SettlementPaid 는 상품주문을 전부 담아 한 스토어 주 약 300건이면 64KB 를 넘어 지급이 500 으로 실패했다.
ALTER TABLE event_outbox MODIFY COLUMN payload MEDIUMTEXT NOT NULL COMMENT '이벤트 봉투 JSON — SNS 한도 256KB 는 SettlementPaidPayload 가 압축으로 맞춘다';
