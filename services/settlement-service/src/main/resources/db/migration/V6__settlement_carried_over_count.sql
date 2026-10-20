-- settlementdb V6 — settlement-service (담당 구지훈)
-- 근거: 결정 대기 2번 A안(10/20 이경석) — 이미 지급된 주에 늦게 들어온 구매확정은 다음 열린 주차 정산서로 이월, 정산서에 "이월 n건" 표기
-- 이월 여부는 항목의 구매확정일이 정산서 주차 밖인지로 판단 (항목에는 원래 구매확정일 그대로) — 건수만 정산서에 저장해 목록에서 바로 표시

ALTER TABLE settlement
  ADD COLUMN carried_over_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '이월 상품주문 수 — 구매확정일이 이 주차 밖(지난 주차가 이미 지급돼 넘어온 건). item_count 에 포함' AFTER item_count;
