-- settlementdb V7 — 이벤트 아웃박스 (담당 구지훈, 10/20)
-- SettlementPaid 를 지급 트랜잭션 안에서 이 테이블에 남기고, 커밋 후 SNS 로 보낸다.
-- 이전에는 커밋 후 바로 SNS 를 불러 실패하면 로그만 남고 이벤트가 사라졌다(명세서 · 메일 누락).
-- 보내지 못한 행은 아웃박스 발행기(30초 주기, ShedLock)가 다시 보낸다. 소비자는 eventId 로 중복을 거른다(최소 1회 전달).

CREATE TABLE event_outbox (
  event_outbox_id BIGINT       NOT NULL AUTO_INCREMENT,
  event_id        CHAR(36)     NOT NULL COMMENT '이벤트 봉투 eventId (UUID) — 다시 보내도 같은 값이라 소비자가 중복을 거름',
  event_type      VARCHAR(50)  NOT NULL COMMENT 'SettlementPaid',
  aggregate_id    VARCHAR(64)  NOT NULL COMMENT '봉투 aggregateId (정산서 ID 등)',
  payload         TEXT         NOT NULL COMMENT '보낼 봉투 JSON 그대로 (docs/events.md 형식)',
  status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING(보내기 전 · 실패) / SENT',
  attempt_count   INT          NOT NULL DEFAULT 0 COMMENT '발행 시도 횟수 (경보 기준)',
  last_error      VARCHAR(200)          COMMENT '마지막 발행 실패 사유',
  sent_at         DATETIME(6)           COMMENT 'SNS 발행 성공 시각',
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_event_outbox PRIMARY KEY (event_outbox_id),
  CONSTRAINT uk_event_outbox_event_id UNIQUE (event_id),
  INDEX idx_event_outbox_status_created (status, created_at)   -- 발행기: 오래된 PENDING 부터
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='이벤트 아웃박스 (트랜잭션과 함께 남기고 커밋 후 발행)';
