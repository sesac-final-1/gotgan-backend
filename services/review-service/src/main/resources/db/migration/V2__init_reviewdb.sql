-- reviewdb V2 — 리뷰 · 사진 · 작성 자격 · 상품문의 · 이벤트 멱등 (V1 은 머지된 빈 자리표시 파일이라 V2 부터)
-- 근거: 스펙 REV-01~09 · 컨벤션 2-7 · 3장 · gotgan-config docs/events.md (OrderItemConfirmed)
-- review 는 order · user 를 호출하지 않음 — 필요한 값은 OrderItemConfirmed payload 로 받아 스냅샷
-- 블라인드 3컬럼: 노션 확인 요청 「신고 · 리뷰 블라인드 (B안)」 10/26 — 상태의 주인은 review, 신고 · 조치 이력은 admindb

-- 리뷰 작성 자격 — OrderItemConfirmed 수신 시 저장 (REV-01)
CREATE TABLE review_eligibility (
  review_eligibility_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '작성 자격 PK',
  order_item_id         BIGINT       NOT NULL COMMENT 'order-service order_item.order_item_id (논리 참조)',
  user_id               BIGINT       NOT NULL COMMENT 'user-service users.user_id (논리 참조) — 구매자 (payload buyerId)',
  product_id            BIGINT       NOT NULL COMMENT 'product-service product.product_id (논리 참조)',
  store_id              BIGINT       NOT NULL COMMENT 'store-service store.store_id (논리 참조)',
  buyer_display_name    VARCHAR(50)  NOT NULL COMMENT '구매자 공개 표시용 이름 스냅샷 (payload buyerDisplayName = order 의 orderer_display_name) — nick 또는 마스킹된 실명, 가공 없이 그대로 표시',
  product_name          VARCHAR(200) NOT NULL COMMENT '구매확정 시점 상품명 스냅샷 (payload productName) — 작성 가능 목록 표시용',
  confirmed_at          DATETIME(6)  NOT NULL COMMENT '구매확정 시각 (payload confirmedAt)',
  expires_at            DATETIME(6)  NOT NULL COMMENT '작성 기한 = confirmed_at + 30일',
  created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  CONSTRAINT pk_review_eligibility PRIMARY KEY (review_eligibility_id),
  CONSTRAINT uk_review_eligibility_order_item UNIQUE (order_item_id),
  INDEX idx_review_eligibility_user_expires (user_id, expires_at)   -- 마이페이지 작성 가능 리뷰
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='리뷰 작성 자격 (상품주문당 1개)';

-- 리뷰 — 상품주문당 1개, 판매자 답글 1개 (REV-02 · 05 · 06)
CREATE TABLE review (
  review_id             BIGINT           NOT NULL AUTO_INCREMENT COMMENT '리뷰 PK',
  review_eligibility_id BIGINT           NOT NULL COMMENT 'review_eligibility.review_eligibility_id',
  order_item_id         BIGINT           NOT NULL COMMENT 'order-service order_item.order_item_id (논리 참조) — 상품주문당 1개',
  user_id               BIGINT           NOT NULL COMMENT 'user-service users.user_id (논리 참조) — 작성자',
  product_id            BIGINT           NOT NULL COMMENT 'product-service product.product_id (논리 참조)',
  store_id              BIGINT           NOT NULL COMMENT 'store-service store.store_id (논리 참조)',
  writer_display_name   VARCHAR(50)      NOT NULL COMMENT '작성자 공개 표시용 이름 (작성 시 review_eligibility.buyer_display_name 복사)',
  rating                TINYINT UNSIGNED NOT NULL COMMENT '별점 1~5',
  title                 VARCHAR(100)              COMMENT '한줄 요약 (목업 title)',
  content               TEXT             NOT NULL COMMENT '본문 (OWASP sanitizer 정화 후 저장)',
  fit                   VARCHAR(20)               COMMENT '사이즈 체감 SMALL / TRUE_TO_SIZE / LARGE — 패션 · 신발가방만 (목업 fit)',
  helpful_count         INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '도움돼요 수 (목업 helpful)',
  reply_content         TEXT                      COMMENT '판매자 답글 (1개, sanitizer 정화)',
  is_blinded            BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '관리자 블라인드 여부 — 구매자 목록 · 평균 · 리뷰 수에서 제외, 판매자 목록엔 "블라인드됨"(답글 불가)',
  blind_reason          VARCHAR(255)              COMMENT '블라인드 사유 (해제하면 NULL)',
  replied_at            DATETIME(6)               COMMENT '판매자 답글 작성 · 수정 시각',
  blinded_at            DATETIME(6)               COMMENT '블라인드 시각 (해제하면 NULL)',
  created_at            DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at            DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  deleted_at            DATETIME(6)               COMMENT '삭제 시각 (soft delete) — 평균 · 분포 집계에서 제외',
  CONSTRAINT pk_review PRIMARY KEY (review_id),
  CONSTRAINT uk_review_order_item UNIQUE (order_item_id),
  CONSTRAINT fk_review_review_eligibility FOREIGN KEY (review_eligibility_id) REFERENCES review_eligibility (review_eligibility_id) ON DELETE RESTRICT,
  CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
  INDEX idx_review_product_created (product_id, created_at),   -- 상품 상세 리뷰 탭 최신순
  INDEX idx_review_product_rating (product_id, rating),        -- 별점 필터 · 분포
  INDEX idx_review_store_created (store_id, created_at),       -- 판매자 리뷰 관리
  INDEX idx_review_user_created (user_id, created_at)          -- 마이페이지 내 리뷰
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 리뷰';

-- 리뷰 사진 — 최대 3장 · 10MB (REV-03)
CREATE TABLE review_image (
  review_image_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '리뷰 사진 PK',
  review_id       BIGINT       NOT NULL COMMENT 'review.review_id',
  image_key       VARCHAR(300) NOT NULL COMMENT 'S3 images 버킷 키 (reviews/...)',
  sort_order      INT UNSIGNED NOT NULL COMMENT '표시 순서 0~2',
  file_size       INT UNSIGNED NOT NULL COMMENT '파일 크기 (byte, 10MB 이하)',
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  CONSTRAINT pk_review_image PRIMARY KEY (review_image_id),
  CONSTRAINT uk_review_image_review_sort UNIQUE (review_id, sort_order),
  CONSTRAINT fk_review_image_review FOREIGN KEY (review_id) REFERENCES review (review_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='리뷰 사진';

-- 상품문의 · 답변 (REV-07~09)
CREATE TABLE product_inquiry (
  product_inquiry_id BIGINT      NOT NULL AUTO_INCREMENT COMMENT '상품문의 PK',
  user_id            BIGINT      NOT NULL COMMENT 'user-service users.user_id (논리 참조) — 작성자',
  product_id         BIGINT      NOT NULL COMMENT 'product-service product.product_id (논리 참조)',
  store_id           BIGINT      NOT NULL COMMENT 'store-service store.store_id (논리 참조) — 판매자 문의 목록 키',
  content            TEXT        NOT NULL COMMENT '문의 본문 (sanitizer 정화)',
  answer_content     TEXT                 COMMENT '판매자 답변 (sanitizer 정화)',
  is_secret          BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '비밀글 — 작성자 · 판매자만 본문 확인',
  answered_at        DATETIME(6)          COMMENT '답변 작성 · 수정 시각 (NULL = 미답변)',
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  deleted_at         DATETIME(6)          COMMENT '삭제 시각 (soft delete)',
  CONSTRAINT pk_product_inquiry PRIMARY KEY (product_inquiry_id),
  INDEX idx_product_inquiry_product_created (product_id, created_at),   -- 상품 상세 문의 탭
  INDEX idx_product_inquiry_store_answered (store_id, answered_at),     -- 판매자 미답변 필터
  INDEX idx_product_inquiry_user_created (user_id, created_at)          -- 마이페이지 내 문의
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품문의 · 답변';

-- 이벤트 소비 멱등 (OrderItemConfirmed) — 처리와 같은 트랜잭션에서 INSERT (컨벤션 2-7 · events.md)
CREATE TABLE processed_event (
  processed_event_id BIGINT      NOT NULL AUTO_INCREMENT COMMENT '처리 이벤트 PK',
  event_id           VARCHAR(36) NOT NULL COMMENT '이벤트 봉투 eventId (UUID)',
  event_type         VARCHAR(50) NOT NULL COMMENT 'OrderItemConfirmed',
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '처리 시각',
  CONSTRAINT pk_processed_event PRIMARY KEY (processed_event_id),
  CONSTRAINT uk_processed_event_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='소비한 이벤트 ID (중복 처리 방지)';
