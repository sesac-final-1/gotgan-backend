-- settlementdb V3 — settlement-service (담당 구지훈)
-- 근거: 요구사항 STL-01~09 · 05장 정산 규칙 · events.md OrderItemConfirmed · 컨벤션 3장
-- 컬럼 순서: PK → 같은 서비스 FK → 다른 서비스 ID → 비즈니스 → 상태 · 플래그 → 도메인 시간 → 감사 컬럼 → version (컨벤션 3-9)
-- V1(shedlock) · V2(settlement_store · processed_event)는 머지돼 수정 금지 — 나머지 정산 테이블을 V3 로 추가.
-- 정산 규칙: 지급액 = 구매확정 매출 − 판매수수료(카테고리별) − 결제수수료 2.2%, 원 단위 내림, 포인트 사용분은 플랫폼 부담(매출은 포인트 차감 전 금액)

-- 카테고리별 판매수수료율 — 변경은 새 행(적용 시작일), 적재 시점에 유효한 행을 찾아 settlement_item 에 스냅샷
CREATE TABLE category_commission (
  category_commission_id BIGINT       NOT NULL AUTO_INCREMENT,
  category_id            BIGINT       NOT NULL COMMENT 'product-service category.category_id (논리 참조) — 상위 카테고리',
  commission_rate        DECIMAL(5,2) NOT NULL COMMENT '판매수수료율 (%) 예: 11.00 — 결제수수료 2.2% 는 별도',
  effective_from         DATE         NOT NULL COMMENT '적용 시작일 (KST) — 이 날 0시 이후 구매확정분부터. 변경은 판매자 공지 후 7일 뒤',
  created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_category_commission PRIMARY KEY (category_commission_id),
  CONSTRAINT uk_category_commission_category_effective UNIQUE (category_id, effective_from)  -- 같은 날 두 번 예약 방지 · 적재 시 최신 행 조회
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='카테고리별 판매수수료율 (STL-01)';

-- 주간 정산서 — 스토어 × 주차 1건 (두 번 집계해도 1건). 금액은 settlement_item 합계
CREATE TABLE settlement (
  settlement_id       BIGINT       NOT NULL AUTO_INCREMENT,
  store_id            BIGINT       NOT NULL COMMENT 'store-service store.store_id (논리 참조) — settlement_store 로 스토어명 · 계좌 확인',
  week_start          DATE         NOT NULL COMMENT '정산 주차 시작일 (월요일, KST)',
  week_end            DATE         NOT NULL COMMENT '정산 주차 종료일 (일요일, KST)',
  item_count          INT UNSIGNED NOT NULL COMMENT '정산 대상 상품주문 수',
  sales_amt           BIGINT       NOT NULL COMMENT '구매확정 매출 (원) = Σ item_amt',
  commission_amt      BIGINT       NOT NULL COMMENT '판매수수료 (원) = Σ commission_amt',
  payment_fee_amt     BIGINT       NOT NULL COMMENT '결제수수료 (원) = Σ payment_fee_amt',
  payout_amt          BIGINT       NOT NULL COMMENT '지급액 (원) = 매출 − 판매수수료 − 결제수수료',
  status              VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED(지급예정) / HELD(지급보류) / PAID(지급완료)',
  hold_reason         VARCHAR(255)          COMMENT '보류 사유 — 스토어 휴면 · 정지(자동) 또는 관리자 입력',
  scheduled_pay_date  DATE         NOT NULL COMMENT '지급 예정일 (KST)',
  held_at             DATETIME(6)           COMMENT '보류 시각',
  paid_at             DATETIME(6)           COMMENT '지급 완료 시각 — SettlementPaid 발행 기준',
  created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '집계 시각',
  updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  version             BIGINT       NOT NULL DEFAULT 0 COMMENT '낙관적 락(@Version) — 지급 실행과 보류가 동시에 저장되는 것 방지',
  CONSTRAINT pk_settlement PRIMARY KEY (settlement_id),
  CONSTRAINT uk_settlement_store_week UNIQUE (store_id, week_start),  -- 스토어 × 주차 1건 — 집계 재실행 · "지금 집계" 이중 클릭 방지
  INDEX idx_settlement_week_status (week_start, status)              -- 관리자 정산 목록: 주차 선택 + 상태 필터
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주간 정산서 (STL-03 · 05 · 07 · 09)';

-- 정산 대상 상품주문 — OrderItemConfirmed 1건 = 1행. 수수료는 적재 시점 값으로 스냅샷(이후 율이 바뀌어도 그대로)
CREATE TABLE settlement_item (
  settlement_item_id BIGINT       NOT NULL AUTO_INCREMENT,
  settlement_id      BIGINT                COMMENT '주간 집계 때 연결 — 집계 전에는 NULL',
  order_item_id      BIGINT       NOT NULL COMMENT 'order-service order_item.order_item_id (논리 참조)',
  order_no           VARCHAR(20)  NOT NULL COMMENT 'order-service orders.order_no (논리 참조) — 판매자 정산 상세 표시용',
  store_id           BIGINT       NOT NULL COMMENT 'store-service store.store_id (논리 참조)',
  product_id         BIGINT       NOT NULL COMMENT 'product-service product.product_id (논리 참조)',
  category_id        BIGINT       NOT NULL COMMENT 'product-service category.category_id (논리 참조) — 상위 카테고리, 수수료율 조회 기준',
  item_amt           BIGINT       NOT NULL COMMENT '상품주문 금액 (원) — 포인트 차감 전 = 판매자 매출',
  commission_rate    DECIMAL(5,2) NOT NULL COMMENT '적재 시점 판매수수료율 (%) 스냅샷',
  commission_amt     BIGINT       NOT NULL COMMENT '판매수수료 (원) = item_amt × commission_rate, 원 단위 내림',
  payment_fee_amt    BIGINT       NOT NULL COMMENT '결제수수료 (원) = item_amt × 2.2%, 원 단위 내림',
  confirmed_at       DATETIME(6)  NOT NULL COMMENT '구매확정 시각 (KST) — 정산 주차 판정 기준',
  created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '적재 시각',
  CONSTRAINT pk_settlement_item PRIMARY KEY (settlement_item_id),
  CONSTRAINT uk_settlement_item_order_item_id UNIQUE (order_item_id),  -- 상품주문 1건은 한 번만 정산 (processed_event 와 이중 방어)
  CONSTRAINT fk_settlement_item_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (settlement_id) ON DELETE RESTRICT,
  INDEX idx_settlement_item_store_confirmed (store_id, confirmed_at)   -- 주간 집계: 스토어별 지난주 구매확정분 (다른 서비스 ID — 수동 인덱스, 컨벤션 3-8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='정산 대상 상품주문 (STL-02 · 04)';

-- 지급 실행 기록 (INSERT 전용 — created_at만). 지급 시점의 정산 계좌를 스냅샷 — 명세서 · 감사용
CREATE TABLE payout (
  payout_id      BIGINT       NOT NULL AUTO_INCREMENT,
  settlement_id  BIGINT       NOT NULL,
  payout_amt     BIGINT       NOT NULL COMMENT '지급액 (원) — settlement.payout_amt 와 같음',
  bank_name      VARCHAR(50)  NOT NULL COMMENT '지급 은행 (settlement_store 스냅샷)',
  account_number VARCHAR(50)  NOT NULL COMMENT '지급 계좌번호 (settlement_store 스냅샷)',
  account_holder VARCHAR(50)  NOT NULL COMMENT '예금주 (settlement_store 스냅샷)',
  paid_at        DATETIME(6)  NOT NULL COMMENT '지급 실행 시각 (애플리케이션이 기록)',
  created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_payout PRIMARY KEY (payout_id),
  CONSTRAINT uk_payout_settlement_id UNIQUE (settlement_id),  -- 정산서 1건은 한 번만 지급 (일괄 지급 재요청 방지)
  CONSTRAINT fk_payout_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (settlement_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='지급 실행 기록 (STL-06)';
