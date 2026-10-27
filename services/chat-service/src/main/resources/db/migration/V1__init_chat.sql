-- chatdb — 1:1 채팅 (docs/api/chat.md 8절, 10/27). 담당 jihoon-devstu

-- ShedLock 잠금 테이블 (메시지 90일 보관 삭제를 한 파드만, 가이드라인 10장)
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL COMMENT '잠금 이름 = @SchedulerLock(name)',
  lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 만료 시각',
  locked_at  TIMESTAMP(3) NOT NULL COMMENT '잠금 획득 시각',
  locked_by  VARCHAR(255) NOT NULL COMMENT '잠금을 잡은 인스턴스',
  CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ShedLock 스케줄러 잠금';

CREATE TABLE chat_room (
  chat_room_id    BIGINT      NOT NULL AUTO_INCREMENT,
  type            VARCHAR(20) NOT NULL COMMENT 'STORE_INQUIRY(스토어 톡) · SELLER_SUPPORT(판매자 운영 상담) · BUYER_SUPPORT(고객센터 1:1 상담)',
  buyer_id        BIGINT      NOT NULL DEFAULT 0 COMMENT 'user-service 회원 (논리 참조) — 판매자 상담은 0 (UNIQUE 가 NULL 을 무시하지 않게)',
  store_id        BIGINT      NOT NULL DEFAULT 0 COMMENT 'store-service 스토어 (논리 참조) — 고객 상담은 0',
  status          VARCHAR(20)          COMMENT '상담방만 OPEN · RESOLVED, 스토어 톡은 NULL',
  last_message_id BIGINT               COMMENT '목록 정렬 · 미리보기',
  last_message_at DATETIME(6)          COMMENT '마지막 메시지 시각 — 목록 정렬 · 빈 방 정리',
  created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_chat_room PRIMARY KEY (chat_room_id),
  CONSTRAINT uk_chat_room UNIQUE (type, buyer_id, store_id),   -- 같은 방 기준: 스토어 톡 = 구매자 × 스토어, 판매자 상담 = 스토어, 고객 상담 = 회원
  INDEX idx_chat_room_store (store_id, last_message_at),
  INDEX idx_chat_room_buyer (buyer_id, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='채팅방';

CREATE TABLE chat_message (
  chat_message_id BIGINT        NOT NULL AUTO_INCREMENT,
  chat_room_id    BIGINT        NOT NULL,
  sender_id       BIGINT                 COMMENT '보낸 회원 — SYSTEM 은 NULL',
  sender_role     VARCHAR(10)   NOT NULL COMMENT 'BUYER · SELLER · ADMIN · SYSTEM',
  kind            VARCHAR(10)   NOT NULL COMMENT 'TEXT · CONTEXT(문맥 카드) · SYSTEM',
  content         VARCHAR(1000) NOT NULL,
  context_type    VARCHAR(20)            COMMENT 'PRODUCT · ORDER · ORDER_ITEM · SETTLEMENT · STORE · REPORT · CHAT_ROOM',
  context_id      BIGINT,
  context_title   VARCHAR(200)           COMMENT '카드 만들 때의 제목 스냅샷',
  created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_chat_message PRIMARY KEY (chat_message_id),
  CONSTRAINT fk_chat_message_room FOREIGN KEY (chat_room_id) REFERENCES chat_room (chat_room_id) ON DELETE RESTRICT,
  INDEX idx_chat_message_room (chat_room_id, chat_message_id),
  INDEX idx_chat_message_created (created_at)          -- 90일 보관 삭제 (8-1절)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='채팅 메시지 — 90일 뒤 자동 삭제';

CREATE TABLE chat_read (
  chat_room_id         BIGINT      NOT NULL,
  reader_key           VARCHAR(30) NOT NULL COMMENT 'USER#{id} · STORE#{id}(판매자는 스토어 단위) · ADMIN(관리자 전원 공용)',
  last_read_message_id BIGINT      NOT NULL,
  updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_chat_read PRIMARY KEY (chat_room_id, reader_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='방마다 읽은 위치';
