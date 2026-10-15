-- userdb V2 — user-service (담당 이경석). V1__init.sql 은 빈 자리표시로 이미 적용돼 수정 금지 → 초기 스키마를 V2 로 추가
-- 근거: API 명세 통합본(user · 내부 API · 이벤트) · 요구사항 USR-01~10 · PNT-01~05 · 컨벤션 3장
-- 컬럼 순서: PK → 같은 서비스 FK → 다른 서비스 ID → 비즈니스 → 상태 → 도메인 시간 → 감사 컬럼 (컨벤션 3-9)
-- 계정(이메일 · 비밀번호 · 역할)의 주인은 Keycloak(keycloakdb). userdb는 프로필 · 배송지 · 포인트 · 등급만 갖고 keycloak_id로 연결

-- 회원 — 테이블명 users (MySQL 키워드 user 회피, 컨벤션 3-1 예외)
CREATE TABLE users (
  user_id              BIGINT       NOT NULL AUTO_INCREMENT,
  keycloak_id          VARCHAR(36)           COMMENT 'Keycloak 사용자 ID(UUID). 자체 가입: 가입 트랜잭션에서 Keycloak 생성 직후 채움(실패 시 행 삭제) / 소셜: 첫 로그인 시 이 값으로 행 생성',
  email                VARCHAR(100) NOT NULL COMMENT '로그인 ID = Keycloak username. 관리자 검색 · 표시용 사본 (소셜은 Keycloak First Broker Login에서 필수 입력)',
  name                 VARCHAR(50)  NOT NULL COMMENT '이름',
  nick                 VARCHAR(30)           COMMENT '닉네임 (선택)',
  phone                VARCHAR(20)           COMMENT '휴대폰 번호 예: 010-1234-5678 — 소셜 가입은 비어 있을 수 있음, 주문 전 입력 받음',
  signup_provider      VARCHAR(20)  NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL / KAKAO / GOOGLE / NAVER — 가입 경로 (표시 · 통계용, 인증 판단에는 쓰지 않음). 소셜 계정 연결은 Keycloak이 관리',
  point_balance        BIGINT       NOT NULL DEFAULT 0 COMMENT '포인트 잔액 (원). 차감은 조건부 UPDATE (point_balance >= :p) — 음수 금지',
  total_confirmed_amt  BIGINT       NOT NULL DEFAULT 0 COMMENT '누적 구매확정 금액 (원) — OrderItemConfirmed 수신 시 가산, 등급 산정 기준',
  grade                VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / SILVER / GOLD — 혜택은 표시만 (적립률 5% 고정)',
  status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED — 정지 시 Keycloak 계정 enabled=false 동기화',
  status_reason        VARCHAR(255)          COMMENT '이용정지 · 해제 사유 (관리자 입력 필수)',
  suspended_at         DATETIME(6)           COMMENT '이용정지 시각 (애플리케이션이 기록)',
  last_login_at        DATETIME(6)           COMMENT '마지막 로그인 시각 (애플리케이션이 기록) — 관리자 회원 상세',
  created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '가입 시각 (= joinedAt)',
  updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at           DATETIME(6)           COMMENT '소프트 삭제 시각 (회원 물리 DELETE 금지)',
  CONSTRAINT pk_users PRIMARY KEY (user_id),
  CONSTRAINT uk_users_email UNIQUE (email),
  CONSTRAINT uk_users_keycloak_id UNIQUE (keycloak_id),
  INDEX idx_users_status_created (status, created_at),  -- 관리자 회원 목록: 상태 필터 + 최신순
  INDEX idx_users_name (name)                           -- 관리자 검색: 이름 (이메일은 uk 인덱스 사용)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 프로필 · 포인트 잔액 · 등급 (계정은 Keycloak)';

-- 배송지
CREATE TABLE address (
  address_id      BIGINT       NOT NULL AUTO_INCREMENT,
  user_id         BIGINT       NOT NULL,
  label           VARCHAR(30)  NOT NULL COMMENT '배송지 이름 예: 집, 회사',
  receiver_name   VARCHAR(50)  NOT NULL COMMENT '수령인',
  receiver_phone  VARCHAR(20)  NOT NULL COMMENT '수령인 연락처',
  zipcode         VARCHAR(10)  NOT NULL COMMENT '우편번호',
  address1        VARCHAR(200) NOT NULL COMMENT '기본 주소',
  address2        VARCHAR(200)          COMMENT '상세 주소',
  is_default      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '기본 배송지 여부 — 회원당 1개는 서비스에서 보장 (지정 시 기존 기본 해제를 같은 트랜잭션에서)',
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at      DATETIME(6)           COMMENT '소프트 삭제 시각',
  CONSTRAINT pk_address PRIMARY KEY (address_id),
  CONSTRAINT fk_address_users FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE RESTRICT,
  INDEX idx_address_user_default (user_id, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원 배송지';

-- 포인트 이력 (INSERT 전용 — created_at만)
CREATE TABLE point_history (
  point_history_id  BIGINT       NOT NULL AUTO_INCREMENT,
  user_id           BIGINT       NOT NULL,
  order_item_id     BIGINT                COMMENT 'order-service order_item.order_item_id (논리 참조) — 상품주문별 배분 · 환원 · 회수 단위. USE는 NULL',
  order_no          VARCHAR(20)           COMMENT 'order-service orders.order_no (논리 참조) — 이력 화면 표시용',
  type              VARCHAR(20)  NOT NULL COMMENT 'EARN(적립) / USE(사용) / RESTORE(사용 환원) / RECLAIM(적립 회수)',
  amount            BIGINT       NOT NULL COMMENT '변동 포인트 (원, 항상 양수) — 증감 방향은 type으로 판단',
  shortfall_amt     BIGINT       NOT NULL DEFAULT 0 COMMENT 'RECLAIM에서 잔액 부족으로 회수하지 못한 차액 (원) — 잔액은 0까지만 회수',
  balance_after     BIGINT       NOT NULL COMMENT '변동 후 잔액 (원)',
  ref_key           VARCHAR(64)  NOT NULL COMMENT '멱등 키 — USE · 보상 RESTORE: sagaId / 클레임 RESTORE · RECLAIM: claim-{claimId}-{orderItemId} / EARN: {eventId}-{orderItemId}',
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_point_history PRIMARY KEY (point_history_id),
  CONSTRAINT uk_point_history_type_ref_key UNIQUE (type, ref_key),
  CONSTRAINT fk_point_history_users FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE RESTRICT,
  INDEX idx_point_history_user_created (user_id, created_at),  -- 내 포인트 이력 최신순 페이징
  INDEX idx_point_history_order_item (order_item_id)           -- 취소 · 반품 시 그 상품주문의 EARN · USE 금액 조회 (다른 서비스 ID — 수동 인덱스, 컨벤션 3-7)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='포인트 적립 · 사용 · 환원 · 회수 이력';

-- 이벤트 소비 멱등 (StoreApproved · OrderPaid · OrderItemConfirmed) — 처리와 같은 트랜잭션에서 INSERT
CREATE TABLE processed_event (
  processed_event_id  BIGINT       NOT NULL AUTO_INCREMENT,
  event_id            VARCHAR(36)  NOT NULL COMMENT '이벤트 봉투 eventId (UUID)',
  event_type          VARCHAR(50)  NOT NULL COMMENT 'StoreApproved / OrderPaid / OrderItemConfirmed',
  created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '처리 시각',
  CONSTRAINT pk_processed_event PRIMARY KEY (processed_event_id),
  CONSTRAINT uk_processed_event_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='소비한 이벤트 ID (중복 처리 방지)';
