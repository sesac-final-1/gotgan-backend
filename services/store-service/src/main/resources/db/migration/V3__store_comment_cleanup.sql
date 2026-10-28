-- storedb V3 — 컬럼 주석만 고침 (담당 박지연, 10/28)
-- 대표 상품 기준이 2~10개로 바뀜(store.md 2절 · 코드, 지훈 J-B) · 10/23 회의로 스토어 휴면(DORMANT) 제거 — 상태는 ACTIVE / SUSPENDED / CLOSED.
-- V1 은 머지돼 수정 금지(Flyway 체크섬) → MODIFY COLUMN 으로 주석만 다시 쓴다. 타입 · NULL · 기본값은 V1 그대로.

ALTER TABLE store_application
  MODIFY COLUMN representative_products JSON NOT NULL COMMENT '대표 상품 2~10개 [{name, subCategory, price}] — 심사 화면 표시용 (검색 · 집계 없음)';

ALTER TABLE store
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED(관리자 정지) / CLOSED(판매자 폐점 — 되돌릴 수 없음)';

ALTER TABLE store_status_history
  MODIFY COLUMN actor_type VARCHAR(10) NOT NULL COMMENT 'SELLER(폐점) / ADMIN(정지 · 해제)';
