-- productdb V1 — product-service (담당 박지연)
-- 근거: 요구사항 CAT-01~03 · PRD-01~15 · 컨벤션 3장
-- 컬럼 순서: PK → 같은 서비스 FK → 다른 서비스 ID → 비즈니스 → 상태 · 플래그 → 도메인 시간 → 감사 컬럼 (컨벤션 3-9)
-- 머지된 파일은 수정 금지 — 바꿀 때는 V2 부터.

-- 카테고리 — 상위/하위 2단계 (깊이 · 하위에만 상품 등록 · 같은 부모 아래 이름 중복은 서비스 검증)
CREATE TABLE category (
  category_id BIGINT      NOT NULL AUTO_INCREMENT,
  parent_id   BIGINT               COMMENT '상위 카테고리 — 상위는 NULL',
  name        VARCHAR(50) NOT NULL COMMENT '카테고리 이름',
  sort_order  INT         NOT NULL DEFAULT 0 COMMENT '같은 부모 안의 정렬 순서 (작을수록 먼저)',
  is_active   BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '사용 여부 — 삭제 없음, FALSE 면 공개 트리에서 숨김 (store · settlement 가 category_id 를 논리 참조)',
  created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_category PRIMARY KEY (category_id),
  CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (category_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 카테고리 (상위/하위 2단계)';

-- 상품 — 품절은 저장하지 않고 옵션 재고 합으로 계산 (ON_SALE + 재고 0 = 품절)
CREATE TABLE product (
  product_id              BIGINT       NOT NULL AUTO_INCREMENT,
  category_id             BIGINT       NOT NULL COMMENT '하위 카테고리만 (서비스 검증)',
  store_id                BIGINT       NOT NULL COMMENT 'store-service store.store_id (논리 참조)',
  name                    VARCHAR(100) NOT NULL COMMENT '상품명',
  price                   BIGINT       NOT NULL COMMENT '판매가 (원, 판매자 입력)',
  discount_rate           DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '즉시할인율 % (0~90, 판매자 입력) — 수정 화면 표시용, 비율이라 DECIMAL (컨벤션 3-6)',
  sale_price              BIGINT       NOT NULL COMMENT '할인 적용가 (원) — 서버가 price · discount_rate 로 계산(10원 단위 반올림). 정렬 · 주문 금액 기준',
  description             TEXT                  COMMENT '상세 설명 (sanitizer 정화 HTML)',
  option1_name            VARCHAR(30)           COMMENT '옵션 그룹 1 이름 예: 색상 — 옵션 없으면 NULL',
  option2_name            VARCHAR(30)           COMMENT '옵션 그룹 2 이름 예: 사이즈 — 최대 2개',
  status                  VARCHAR(20)  NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE / STOPPED(판매자 판매중지) / BLOCKED(관리자 강제 판매중지 — 해제하면 STOPPED)',
  status_reason           VARCHAR(255)          COMMENT '강제 판매중지 사유',
  is_store_active         BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '스토어 운영중 여부 — StoreStatusChanged 로 갱신, FALSE 면 목록 · 검색에서 제외',
  store_status_changed_at DATETIME(6)           COMMENT '마지막으로 반영한 StoreStatusChanged 의 occurredAt — 이보다 오래된 이벤트는 무시 (SQS 순서 미보장)',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '등록 시각 (신상품 정렬)',
  updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at              DATETIME(6)           COMMENT '소프트 삭제 시각 (주문 스냅샷 보존, 물리 DELETE 금지)',
  CONSTRAINT pk_product PRIMARY KEY (product_id),
  CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (category_id) ON DELETE RESTRICT,
  INDEX idx_product_store_id (store_id)  -- 판매자 상품 목록 · 스토어 홈 · 스토어 상태 일괄 반영 · 통계 (다른 서비스 ID — 수동 인덱스, 컨벤션 3-8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품';

-- 옵션 조합 — 조합 1개 = 1행, 옵션 없는 상품도 기본 조합 1행 (장바구니 · 주문 · 재고가 옵션 ID 기준)
CREATE TABLE product_option (
  product_option_id BIGINT       NOT NULL AUTO_INCREMENT,
  product_id        BIGINT       NOT NULL,
  option1_value     VARCHAR(30)           COMMENT '옵션 값 1 예: 빨강 — 기본 조합은 NULL',
  option2_value     VARCHAR(30)           COMMENT '옵션 값 2 예: M',
  extra_price       BIGINT       NOT NULL DEFAULT 0 COMMENT '추가금 (원) — 최종가 = product.sale_price + extra_price',
  stock             INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '현재 재고 — 예약은 조건부 UPDATE(stock >= :qty), 판매자 수정은 본 값 비교(stock = :seen)',
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at        DATETIME(6)           COMMENT '소프트 삭제 시각 — 옵션 구성 변경으로 사라진 조합 (주문 · 장바구니 · stock_history 가 참조)',
  CONSTRAINT pk_product_option PRIMARY KEY (product_option_id),
  CONSTRAINT fk_product_option_product FOREIGN KEY (product_id) REFERENCES product (product_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 옵션 조합 · 재고 (조합 중복은 서비스 검증)';

-- 상품 이미지 — 최대 5장, sort_order 0 이 대표. 참조하는 곳이 없어 빼면 물리 삭제
CREATE TABLE product_image (
  product_image_id BIGINT       NOT NULL AUTO_INCREMENT,
  product_id       BIGINT       NOT NULL,
  s3_key           VARCHAR(255) NOT NULL COMMENT 'S3 images 객체 키 (products/ 공개 경로, 조회 URL = IMAGES_BASE_URL/{key})',
  sort_order       INT          NOT NULL DEFAULT 0 COMMENT '갤러리 순서 — 0 이 대표 이미지',
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_product_image PRIMARY KEY (product_image_id),
  CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (product_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 이미지';

-- 재고 원장 (INSERT 전용 — created_at만). Saga 멱등 전용: 판매자 직접 재고 수정은 기록하지 않음
CREATE TABLE stock_history (
  stock_history_id  BIGINT       NOT NULL AUTO_INCREMENT,
  product_option_id BIGINT       NOT NULL,
  type              VARCHAR(20)  NOT NULL COMMENT 'RESERVE(예약 차감) / RELEASE(예약 해제) / RESTORE(취소 · 반품 복원)',
  ref_key           VARCHAR(64)  NOT NULL COMMENT '멱등 키 — RESERVE · RELEASE: order-service saga_instance.saga_id / RESTORE: claim-{claimId}',
  quantity          INT UNSIGNED NOT NULL COMMENT '변동 수량 (항상 양수) — 증감 방향은 type 으로 판단',
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_stock_history PRIMARY KEY (stock_history_id),
  CONSTRAINT uk_stock_history_type_ref_key_option UNIQUE (type, ref_key, product_option_id),  -- 같은 예약 · 해제 · 복원 중복 처리 방지
  CONSTRAINT fk_stock_history_product_option FOREIGN KEY (product_option_id) REFERENCES product_option (product_option_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='재고 예약 · 해제 · 복원 원장';

-- 이벤트 소비 멱등 (StoreStatusChanged) — 처리와 같은 트랜잭션에서 INSERT (userdb 와 같은 형식)
CREATE TABLE processed_event (
  processed_event_id BIGINT      NOT NULL AUTO_INCREMENT,
  event_id           VARCHAR(36) NOT NULL COMMENT '이벤트 봉투 eventId (UUID)',
  event_type         VARCHAR(50) NOT NULL COMMENT 'StoreStatusChanged',
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '처리 시각',
  CONSTRAINT pk_processed_event PRIMARY KEY (processed_event_id),
  CONSTRAINT uk_processed_event_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='소비한 이벤트 ID (중복 처리 방지)';
