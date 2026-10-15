-- settlementdb V2 — 스토어 스냅샷(지급 보류 판단) · 이벤트 멱등. 담당 jihoon-devstu
-- 규칙: 컨벤션 3장. SQS 표준 큐는 순서를 보장하지 않으므로 상태를 덮어쓰는 이벤트는 occurredAt 으로 옛 이벤트를 무시한다 (docs/events.md 순서 규칙, 10/15 결정 A안)

-- StoreApproved 스냅샷 + StoreStatusChanged 로 지급 보류 판단 (store 를 동기 호출하지 않음 — 장애 전파 방지)
CREATE TABLE settlement_store (
  settlement_store_id     BIGINT       NOT NULL AUTO_INCREMENT,
  store_id                BIGINT       NOT NULL COMMENT 'store-service 스토어 ID (다른 서비스 ID — FK 없음)',
  store_name              VARCHAR(100) NOT NULL COMMENT 'StoreApproved 스냅샷 — 스토어명',
  bank_name               VARCHAR(50)           COMMENT 'StoreApproved 스냅샷 — 정산 계좌 은행',
  account_number          VARCHAR(50)           COMMENT 'StoreApproved 스냅샷 — 정산 계좌번호',
  account_holder          VARCHAR(50)           COMMENT 'StoreApproved 스냅샷 — 예금주',
  store_status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DORMANT / SUSPENDED — ACTIVE 가 아니면 지급 보류',
  store_status_changed_at DATETIME(6)           COMMENT '마지막으로 반영한 StoreStatusChanged 의 occurredAt — 이보다 오래된 이벤트는 무시 (SQS 순서 미보장)',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_settlement_store PRIMARY KEY (settlement_store_id),
  CONSTRAINT uk_settlement_store_store_id UNIQUE (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='정산용 스토어 스냅샷 (StoreApproved · StoreStatusChanged)';

-- 이벤트 소비 멱등 — 처리와 같은 트랜잭션에서 INSERT (productdb · userdb 와 같은 형식)
CREATE TABLE processed_event (
  processed_event_id BIGINT      NOT NULL AUTO_INCREMENT,
  event_id           VARCHAR(36) NOT NULL COMMENT '이벤트 봉투 eventId (UUID)',
  event_type         VARCHAR(50) NOT NULL COMMENT 'StoreApproved · StoreStatusChanged · OrderItemConfirmed',
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '처리 시각',
  CONSTRAINT pk_processed_event PRIMARY KEY (processed_event_id),
  CONSTRAINT uk_processed_event_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='소비한 이벤트 ID (중복 처리 방지)';
