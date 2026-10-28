-- reviewdb V3 — 리뷰 "도움돼요" 범위 제외 (10/28 결정) — 쓰지 않는 helpful_count 를 지운다 (V2 는 머지돼서 새 번호로)
ALTER TABLE review DROP COLUMN helpful_count;
