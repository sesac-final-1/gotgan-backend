-- admindb V2 — F-1 장애 시연 내리기 기록 (11/7). 담당 jihoon-devstu
-- 관리자가 서비스 파드를 언제 · 누가 · 몇 개 내렸고 언제 복구됐나(감사 기록 · 타임라인). 파드 상태의 주인은 쿠버네티스 — 여기는 기록만.
-- 규칙: 컨벤션 3장 (단수형 테이블 · PK {테이블}_id · idx_ · DATETIME(6) · utf8mb4_unicode_ci · 모든 COMMENT)

CREATE TABLE chaos_event (
  chaos_event_id   BIGINT        NOT NULL AUTO_INCREMENT,
  service_name     VARCHAR(50)   NOT NULL COMMENT '내린 서비스 — 파드 라벨 app (order-service 형식)',
  scope            VARCHAR(10)   NOT NULL COMMENT 'ONE(Ready 파드 1개) / ALL(전부)',
  reason           VARCHAR(255)           COMMENT '내린 이유 (선택)',
  deleted_pods     VARCHAR(1000) NOT NULL COMMENT '삭제한 파드 이름 — 쉼표로 구분',
  actor_id         BIGINT        NOT NULL COMMENT '내린 관리자 user_id (X-User-Id)',
  killed_at        DATETIME(6)   NOT NULL COMMENT '내린 시각 (KST)',
  recovered_at     DATETIME(6)            COMMENT '복구 시각 — 내린 뒤 원하는 레플리카 수만큼 Ready 가 된 것을 상태 조회가 처음 본 시각. NULL = 복구 전',
  created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_chaos_event PRIMARY KEY (chaos_event_id),
  INDEX idx_chaos_event_service (service_name, chaos_event_id)   -- 서비스별 마지막 내리기(재시도 제한 · 복구 판정)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='장애 시연 내리기 기록';
