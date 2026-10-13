-- orderdb V1 — 담당 jongbeen97 가 작성한다.
-- 규칙: 컨벤션 3장 (단수형 테이블 · PK {테이블}_id · pk_/fk_/uk_/idx_ · DATETIME(6) · utf8mb4_unicode_ci · 모든 COMMENT)
-- 예시 DDL: 가이드라인 06장 (orderdb) · 07장 (paymentdb). 머지된 파일은 수정 금지 — 바꿀 때는 V2 부터.

-- ShedLock 잠금 테이블 (스케줄러 중복 실행 방지, 가이드라인 10장)
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL COMMENT '잠금 이름 = @SchedulerLock(name)',
  lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 만료 시각',
  locked_at  TIMESTAMP(3) NOT NULL COMMENT '잠금 획득 시각',
  locked_by  VARCHAR(255) NOT NULL COMMENT '잠금을 잡은 인스턴스',
  CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ShedLock 스케줄러 잠금';

-- 장바구니 원본 (Redis cart:{userId} 는 캐시 — 10/13 결정 4, cart/package-info.java)
CREATE TABLE cart_item (
  cart_item_id      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '장바구니 항목 PK',
  user_id           BIGINT       NOT NULL COMMENT 'user-service users.user_id (논리 참조)',
  product_id        BIGINT       NOT NULL COMMENT 'product-service product.product_id (논리 참조)',
  product_option_id BIGINT       NOT NULL COMMENT 'product-service product_option.product_option_id (논리 참조)',
  quantity          INT UNSIGNED NOT NULL COMMENT '담은 수량',
  checked           BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '주문 대상 선택 여부',
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  CONSTRAINT pk_cart_item PRIMARY KEY (cart_item_id),
  CONSTRAINT uk_cart_item_user_option UNIQUE (user_id, product_option_id),
  INDEX idx_cart_item_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='장바구니 항목 (원본, Redis 캐시의 출처)';
