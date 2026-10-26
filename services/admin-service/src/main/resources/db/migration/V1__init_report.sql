-- admindb V1 — 신고 · 조치 이력 (ADR-0001 예외 10/26, B안). 담당 jihoon-devstu
-- admin 은 다른 서비스 데이터를 갖지 않는다: 리뷰 블라인드는 review, 회원 정지는 user 가 상태의 주인이고 여기는 "누가 무엇을 신고했고 어떻게 처리했나"만.
-- 규칙: 컨벤션 3장 (단수형 테이블 · PK {테이블}_id · pk_/fk_/uk_/idx_ · DATETIME(6) · utf8mb4_unicode_ci · 모든 COMMENT)

-- 관리자 조치 — 처리 1번 = 1행 (INSERT 전용). 같은 대상에 열린 신고를 한꺼번에 처리한다
CREATE TABLE moderation_action (
  moderation_action_id BIGINT       NOT NULL AUTO_INCREMENT,
  target_type          VARCHAR(20)  NOT NULL COMMENT 'REVIEW / USER',
  target_id            BIGINT       NOT NULL COMMENT 'review-service review_id · user-service user_id (다른 서비스 ID — FK 없음)',
  action               VARCHAR(20)  NOT NULL COMMENT 'BLIND / UNBLIND (리뷰) · SUSPEND / UNSUSPEND (회원) · DISMISS (기각 — 대상 변경 없음)',
  reason               VARCHAR(255) NOT NULL COMMENT '처리 사유 — 블라인드 · 정지면 대상 서비스에도 그대로 전달',
  suspend_days         INT                   COMMENT 'SUSPEND 기간(일, 1~365) — NULL 이면 영구 (user PATCH /internal/users/{id}/status 의 days)',
  admin_id             BIGINT       NOT NULL COMMENT '처리한 관리자 user_id (X-User-Id)',
  report_count         INT          NOT NULL COMMENT '이 처리로 닫힌 열린 신고 수 (신고 없이 처리하면 0)',
  created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_moderation_action PRIMARY KEY (moderation_action_id),
  INDEX idx_moderation_action_target (target_type, target_id, created_at)   -- 대상별 조치 이력 (회원 상세 제재 이력)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='관리자 조치 이력';

-- 신고 — 신고자 · 대상당 1건 (중복 신고 409)
CREATE TABLE report (
  report_id            BIGINT       NOT NULL AUTO_INCREMENT,
  target_type          VARCHAR(20)  NOT NULL COMMENT 'REVIEW / USER',
  target_id            BIGINT       NOT NULL COMMENT 'review_id · user_id (다른 서비스 ID — FK 없음)',
  reporter_id          BIGINT       NOT NULL COMMENT '신고한 회원 user_id (X-User-Id)',
  reporter_role        VARCHAR(20)  NOT NULL COMMENT 'BUYER / SELLER — 판매자 검토 요청은 SELLER',
  reason_code          VARCHAR(30)  NOT NULL COMMENT 'SPAM · ABUSE · FALSE_INFO · PRIVACY · OBSCENE · FRAUD · SELLER_REQUEST · ETC',
  detail               VARCHAR(500)          COMMENT '신고 내용 (선택)',
  target_summary       VARCHAR(500)          COMMENT '신고 당시 대상 요약 — 리뷰 본문 앞부분 · 회원 이름 (나중에 수정 · 삭제돼도 근거로)',
  status               VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN(처리 대기) / RESOLVED(조치함) / DISMISSED(기각)',
  moderation_action_id BIGINT                COMMENT '이 신고를 닫은 조치',
  created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_report PRIMARY KEY (report_id),
  CONSTRAINT uk_report_target_reporter UNIQUE (target_type, target_id, reporter_id),   -- 같은 사람이 같은 대상을 두 번 신고하지 못하게
  CONSTRAINT fk_report_moderation_action FOREIGN KEY (moderation_action_id) REFERENCES moderation_action (moderation_action_id) ON DELETE RESTRICT,
  INDEX idx_report_status_target (status, target_type, target_id)          -- 관리자 목록: 처리 대기 신고를 대상별로 묶기
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 · 리뷰 신고';
