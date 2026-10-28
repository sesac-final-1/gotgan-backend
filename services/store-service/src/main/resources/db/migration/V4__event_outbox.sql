-- storedb V4 — 이벤트 아웃박스 (담당 박지연, 10/28)
-- 승인 · 반려 · 보완요청 · 상태 변경 이벤트를 업무 트랜잭션 안에서 이 테이블에 남기고, 커밋 직후 SNS 로 보낸다.
-- 실패 · 미발행 행은 발행기(ShedLock)가 다시 보낸다 — order outbox_event(V2 · V6) · settlement event_outbox 와 같은 방식.
-- order 와 다른 점: eventId 는 부르는 쪽이 정하고(StoreApproved 는 신청 ID 이름 기반 UUID), occurredAt 은 앱 Clock 으로 넣은 occurred_at.

CREATE TABLE outbox_event (
  outbox_event_id BIGINT       NOT NULL AUTO_INCREMENT,
  event_id        VARCHAR(36)  NOT NULL COMMENT '봉투 eventId (UUID) — 소비자 processed_event 멱등 키, 같은 이벤트 두 번 저장 방지',
  event_type      VARCHAR(50)  NOT NULL COMMENT 'StoreApproved / StoreApplicationRejected / StoreApplicationSupplementRequested / StoreStatusChanged',
  aggregate_id    VARCHAR(40)  NOT NULL COMMENT '봉투 aggregateId — 스토어 ID 또는 입점 신청 ID',
  payload         JSON         NOT NULL COMMENT '이벤트 payload (config docs/events.md)',
  occurred_at     DATETIME(6)  NOT NULL COMMENT '봉투 occurredAt — 업무 시각(앱 Clock). 다시 보내도 같은 값',
  status          VARCHAR(10)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW / SENT',
  attempt_count   INT          NOT NULL DEFAULT 0 COMMENT '발행 시도 횟수 — 한도를 넘으면 ERROR 로그(수동 확인)',
  last_error      VARCHAR(200)          COMMENT '마지막 발행 실패 사유',
  sent_at         DATETIME(6)           COMMENT 'SNS 발행 시각',
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_outbox_event PRIMARY KEY (outbox_event_id),
  CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id),
  INDEX idx_outbox_event_status (status, outbox_event_id)  -- 발행기: 오래된 NEW 부터
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='이벤트 아웃박스 (Transactional Outbox)';
