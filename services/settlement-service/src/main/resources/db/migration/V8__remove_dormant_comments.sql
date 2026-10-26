-- settlementdb V8 — 스토어 휴면 제거에 맞춰 컬럼 주석만 고침 (담당 구지훈, 10/26)
-- 10/23 회의: 스토어 휴면(DORMANT)은 구현하지 않음 — 상태는 ACTIVE / SUSPENDED / CLOSED.
-- V2 · V3 · V4 는 머지돼 수정 금지(Flyway 체크섬) → 주석만 MODIFY COLUMN 으로 다시 쓴다. 타입 · NULL · 기본값은 원래 그대로.

ALTER TABLE settlement_store
  MODIFY COLUMN store_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED / CLOSED — ACTIVE 가 아니면 지급 보류';

ALTER TABLE settlement
  MODIFY COLUMN hold_reason VARCHAR(255) COMMENT '보류 사유 — 스토어 정지 · 폐점(자동) 또는 관리자 입력';

ALTER TABLE settlement_history
  MODIFY COLUMN actor_type VARCHAR(10) NOT NULL COMMENT 'SYSTEM(주간 집계 · 스토어 정지/폐점 자동 보류) / ADMIN — 관리자 ID는 남기지 않음 (storedb 이력과 같은 규칙)';
