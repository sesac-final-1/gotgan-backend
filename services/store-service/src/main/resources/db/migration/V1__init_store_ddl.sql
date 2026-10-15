-- storedb V1 — store-service (담당 박지연)
-- 근거: 요구사항 STR-01~14 · 05장 입점 신청 · 스토어 상태 · 컨벤션 3장
-- 컬럼 순서: PK → 같은 서비스 FK → 다른 서비스 ID → 비즈니스 → 상태 · 플래그 → 도메인 시간 → 감사 컬럼 → version (컨벤션 3-9)
-- store 는 이벤트를 발행만 하므로 processed_event 없음. 머지된 파일은 수정 금지 — 바꿀 때는 V2 부터.

-- ShedLock 잠금 테이블 (스케줄러 중복 실행 방지, 가이드라인 10장)
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL COMMENT '잠금 이름 = @SchedulerLock(name)',
  lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 만료 시각',
  locked_at  TIMESTAMP(3) NOT NULL COMMENT '잠금 획득 시각',
  locked_by  VARCHAR(255) NOT NULL COMMENT '잠금을 잡은 인스턴스',
  CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ShedLock 스케줄러 잠금';

-- 입점 신청서 — 심사 당시 기록이라 승인 후에도 수정하지 않음 (바뀔 수 있는 연락처는 store.cs_phone)
CREATE TABLE store_application (
  store_application_id    BIGINT       NOT NULL AUTO_INCREMENT,
  user_id                 BIGINT       NOT NULL COMMENT 'user-service users.user_id (논리 참조) — 신청자',
  category_id             BIGINT       NOT NULL COMMENT 'product-service category.category_id (논리 참조) — 희망 카테고리',
  biz_type                VARCHAR(20)  NOT NULL COMMENT 'INDIVIDUAL / CORPORATE — 서류는 같고 심사 기준만 다름',
  company_name            VARCHAR(100) NOT NULL COMMENT '상호',
  owner_name              VARCHAR(50)  NOT NULL COMMENT '대표자 — 스토어 홈 판매자 정보로 노출',
  biz_reg_no              VARCHAR(12)  NOT NULL COMMENT '사업자등록번호 000-00-00000 — 스토어 홈 판매자 정보로 노출. 바뀌면 새 사업자(재심사)',
  ecommerce_reg_no        VARCHAR(50)  NOT NULL COMMENT '통신판매업 신고번호',
  manager_phone           VARCHAR(20)  NOT NULL COMMENT '담당자 휴대폰 — 승인 시 store.cs_phone 으로 복사',
  manager_email           VARCHAR(100) NOT NULL COMMENT '심사 결과(승인 · 반려 · 보완요청) 메일 수신 주소 — 이벤트 payload 에 포함',
  business_address        VARCHAR(255) NOT NULL COMMENT '사업장 주소',
  store_name              VARCHAR(50)  NOT NULL COMMENT '신청 당시 스토어명 — 승인 시 store.store_name 으로 복사',
  intro                   TEXT                  COMMENT '스토어 소개 (sanitizer 정화 HTML) — 승인 시 store.intro 초기값',
  representative_products JSON         NOT NULL COMMENT '대표 상품 3개 이상 [{name, subCategory, price}] — 심사 화면 표시용 (검색 · 집계 없음)',
  bank_name               VARCHAR(30)  NOT NULL COMMENT '정산 은행',
  account_no              VARCHAR(30)  NOT NULL COMMENT '정산 계좌번호',
  account_holder          VARCHAR(50)  NOT NULL COMMENT '예금주',
  status                  VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SUPPLEMENT / APPROVED / REJECTED',
  status_reason           VARCHAR(255)          COMMENT '가장 최근 반려 · 보완 사유 (전체는 application_history)',
  marketing_agreed        BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '마케팅 수신 동의 (선택 약관)',
  terms_agreed_at         DATETIME(6)  NOT NULL COMMENT '필수 약관 동의 시각 (애플리케이션이 기록)',
  status_changed_at       DATETIME(6)  NOT NULL COMMENT '마지막 상태 변경 시각 — 7일 자동 반려(SUPPLEMENT) · 30일 재신청 제한(REJECTED) 기준',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '제출 시각',
  updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  version                 BIGINT       NOT NULL DEFAULT 0 COMMENT '낙관적 락(@Version) — 재제출과 자동 반려가 동시에 저장되는 것 방지',
  CONSTRAINT pk_store_application PRIMARY KEY (store_application_id),
  INDEX idx_store_application_user_id (user_id)  -- 진행 중 신청 · 30일 재신청 확인, 내 신청 조회 (다른 서비스 ID — 수동 인덱스, 컨벤션 3-8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='입점 신청서 (진행 중 신청 1건은 서비스 검증)';

-- 입점 서류 — 재제출하면 새 행, 이전 행은 is_current = FALSE 로 보존
CREATE TABLE application_document (
  application_document_id BIGINT       NOT NULL AUTO_INCREMENT,
  store_application_id    BIGINT       NOT NULL,
  doc_type                VARCHAR(30)  NOT NULL COMMENT 'BUSINESS_REG / ECOMMERCE_REG / BANK_COPY / PRODUCT_PHOTO / CATEGORY_CERT',
  s3_key                  VARCHAR(255) NOT NULL COMMENT 'S3 seller-docs 객체 키 (비공개 — 볼 때마다 presigned GET 발급)',
  original_name           VARCHAR(255) NOT NULL COMMENT '원본 파일명 (관리자 화면 표시)',
  content_type            VARCHAR(50)  NOT NULL COMMENT 'application/pdf · image/jpeg · image/png',
  file_size               INT UNSIGNED NOT NULL COMMENT '파일 크기 (바이트, 10MB 이하)',
  is_current              BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '현재 유효한 서류 여부 — 재제출되면 FALSE',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_application_document PRIMARY KEY (application_document_id),
  CONSTRAINT fk_application_document_store_application FOREIGN KEY (store_application_id) REFERENCES store_application (store_application_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='입점 신청 서류';

-- 신청 상태 이력 (INSERT 전용 — created_at만, 가이드라인 06장 order_item_history 와 같은 구조)
CREATE TABLE application_history (
  application_history_id BIGINT       NOT NULL AUTO_INCREMENT,
  store_application_id   BIGINT       NOT NULL,
  from_status            VARCHAR(20)           COMMENT '변경 전 상태 — 최초 신청은 NULL',
  to_status              VARCHAR(20)  NOT NULL COMMENT '변경 후 상태',
  actor_type             VARCHAR(10)  NOT NULL COMMENT 'APPLICANT / ADMIN / SYSTEM(7일 자동 반려) — 관리자 ID는 남기지 않음',
  reason                 VARCHAR(255)          COMMENT '반려 · 보완 사유',
  created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '상태가 바뀐 시각',
  CONSTRAINT pk_application_history PRIMARY KEY (application_history_id),
  CONSTRAINT fk_application_history_store_application FOREIGN KEY (store_application_id) REFERENCES store_application (store_application_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='입점 신청 상태 이력 (STR-08 타임라인)';

-- 스토어 — 승인 시 신청서에서 생성. 1인 1스토어 (Keycloak store_id 속성 · X-Store-Id 가 값 하나)
CREATE TABLE store (
  store_id                BIGINT       NOT NULL AUTO_INCREMENT,
  store_application_id    BIGINT       NOT NULL COMMENT '원래 입점 신청 — 대표자 · 사업자번호는 이 신청서를 조인해 노출',
  user_id                 BIGINT       NOT NULL COMMENT 'user-service users.user_id (논리 참조) — 판매자',
  store_name              VARCHAR(50)  NOT NULL COMMENT '스토어명',
  intro                   TEXT                  COMMENT '스토어 소개 (sanitizer 정화 HTML)',
  image_key               VARCHAR(255)          COMMENT '대표 이미지 S3 images 키 (stores/ 공개 경로)',
  cs_phone                VARCHAR(20)  NOT NULL COMMENT '고객 문의 연락처 — 승인 시 신청서 담당자 휴대폰 복사, 이후 판매자 수정',
  shipping_fee            BIGINT       NOT NULL DEFAULT 3000 COMMENT '스토어 묶음 기본 배송비 (원) — 승인 직후 3,000원',
  free_shipping_threshold BIGINT                COMMENT '무료배송 기준 금액 (원) — NULL 이면 무료배송 없음',
  status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DORMANT(판매자 휴면) / SUSPENDED(관리자 정지)',
  status_reason           VARCHAR(255)          COMMENT '가장 최근 정지 · 해제 사유 (전체는 store_status_history)',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at              DATETIME(6)           COMMENT '소프트 삭제 시각 (물리 DELETE 금지)',
  CONSTRAINT pk_store PRIMARY KEY (store_id),
  CONSTRAINT uk_store_store_application_id UNIQUE (store_application_id),  -- 신청서 하나로 스토어 2개 생성 방지 (승인 이중 클릭)
  CONSTRAINT uk_store_user_id UNIQUE (user_id),                            -- 1인 1스토어
  CONSTRAINT fk_store_store_application FOREIGN KEY (store_application_id) REFERENCES store_application (store_application_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='스토어 · 배송 정책';

-- 스토어 상태 이력 (INSERT 전용 — created_at만). 상태를 건 쪽이 푼다: 휴면은 판매자, 정지는 관리자
CREATE TABLE store_status_history (
  store_status_history_id BIGINT       NOT NULL AUTO_INCREMENT,
  store_id                BIGINT       NOT NULL,
  from_status             VARCHAR(20)           COMMENT '변경 전 상태 — 승인으로 생성될 때만 NULL',
  to_status               VARCHAR(20)  NOT NULL COMMENT '변경 후 상태',
  actor_type              VARCHAR(10)  NOT NULL COMMENT 'SELLER / ADMIN',
  reason                  VARCHAR(255)          COMMENT '사유 — 관리자 정지 · 해제는 필수',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '상태가 바뀐 시각',
  CONSTRAINT pk_store_status_history PRIMARY KEY (store_status_history_id),
  CONSTRAINT fk_store_status_history_store FOREIGN KEY (store_id) REFERENCES store (store_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='스토어 상태 이력 (STR-14)';
