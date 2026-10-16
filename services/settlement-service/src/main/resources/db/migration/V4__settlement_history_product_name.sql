-- settlementdb V4 — settlement-service (담당 구지훈)
-- 근거: API 명세 관리자 정산 상세(GET /api/admin/settlements/{id}) items[].productName · history[] · events.md OrderItemConfirmed productName
-- V3 는 머지돼 수정 금지 — API 명세 대조(10/16)에서 빠진 저장 위치를 V4 로 추가.

-- 정산 상세 items[].productName — 구매확정 시점 상품명 스냅샷 (settlement 는 product 를 동기 호출하지 않음)
ALTER TABLE settlement_item
  ADD COLUMN product_name VARCHAR(100) NOT NULL COMMENT '구매확정 시점 상품명 스냅샷 (OrderItemConfirmed productName) — 정산 상세 표시용' AFTER product_id;

-- 정산서 상태 이력 (INSERT 전용 — created_at만). 관리자 정산 상세의 history[] — 집계 · 보류 · 해제 · 지급
CREATE TABLE settlement_history (
  settlement_history_id BIGINT       NOT NULL AUTO_INCREMENT,
  settlement_id         BIGINT       NOT NULL,
  from_status           VARCHAR(20)           COMMENT '변경 전 상태 — 집계로 생성될 때만 NULL',
  to_status             VARCHAR(20)  NOT NULL COMMENT '변경 후 상태 (SCHEDULED / HELD / PAID)',
  actor_type            VARCHAR(10)  NOT NULL COMMENT 'SYSTEM(주간 집계 · 스토어 휴면/정지 자동 보류) / ADMIN — 관리자 ID는 남기지 않음 (storedb 이력과 같은 규칙)',
  reason                VARCHAR(255)          COMMENT '보류 · 해제 사유',
  created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '상태가 바뀐 시각',
  CONSTRAINT pk_settlement_history PRIMARY KEY (settlement_history_id),
  CONSTRAINT fk_settlement_history_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (settlement_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='정산서 상태 이력 (STL-05 상세 · STL-09)';
