-- paymentdb V1 — 담당 jihoon-devstu
-- 규칙: 컨벤션 3장 (단수형 테이블 · PK {테이블}_id · pk_/fk_/uk_/idx_ · DATETIME(6) · utf8mb4_unicode_ci · 모든 COMMENT)
-- 원본: 가이드라인 07장 paymentdb 예시 DDL. 머지된 파일은 수정 금지 — 바꿀 때는 V2 부터.

CREATE TABLE payment (
  payment_id         BIGINT       NOT NULL AUTO_INCREMENT,
  order_id           BIGINT       NOT NULL COMMENT 'order-service orders.order_id (논리 참조)',
  order_no           VARCHAR(20)  NOT NULL COMMENT 'order-service orders.order_no (논리 참조) — 조회 · 로그용',
  portone_payment_id VARCHAR(64)  NOT NULL COMMENT '포트원 paymentId — 우리가 만들어 넘기는 값 예: pay-20261021-000123 = 멱등 키',
  amount             BIGINT       NOT NULL COMMENT '기대 금액 = 실결제금액 (원) — 포트원 조회 결과와 비교',
  cancelled_amount   BIGINT       NOT NULL DEFAULT 0 COMMENT '누적 취소 금액 (원) — 부분 취소 합계',
  pg_tx_id           VARCHAR(100)          COMMENT '포트원 응답의 PG 거래 ID',
  method             VARCHAR(30)           COMMENT 'CARD / EASY_PAY ...',
  status             VARCHAR(20)  NOT NULL DEFAULT 'READY' COMMENT 'READY / PAID / PARTIAL_CANCELLED / CANCELLED / FAILED',
  paid_at            DATETIME(6)           COMMENT '결제 완료 시각',
  cancelled_at       DATETIME(6)           COMMENT '전액 취소 시각',
  created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  version            BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA @Version — 확인 요청과 웹훅이 동시에 와도 한 번만 반영',
  CONSTRAINT pk_payment PRIMARY KEY (payment_id),
  CONSTRAINT uk_payment_portone_payment_id UNIQUE (portone_payment_id),
  CONSTRAINT uk_payment_order_no UNIQUE (order_no),
  INDEX idx_payment_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제 (포트원)';

CREATE TABLE payment_cancel (
  payment_cancel_id       BIGINT       NOT NULL AUTO_INCREMENT,
  payment_id              BIGINT       NOT NULL,
  order_item_id           BIGINT                COMMENT 'order-service order_item.order_item_id (논리 참조) — 전액 취소면 NULL',
  saga_id                 CHAR(36)              COMMENT 'order-service saga_instance.saga_id (논리 참조) — 같은 취소 재요청 멱등 키',
  cancel_amount           BIGINT       NOT NULL COMMENT '취소 금액 (원)',
  reason                  VARCHAR(200) NOT NULL COMMENT '취소 사유 예: 구매자 취소 / 반품 / 결제 금액 불일치 자동 취소',
  portone_cancellation_id VARCHAR(64)           COMMENT '포트원 취소 응답의 cancellation id',
  cancelled_at            DATETIME(6)  NOT NULL COMMENT '포트원 취소 완료 시각 (애플리케이션이 기록)',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_payment_cancel PRIMARY KEY (payment_cancel_id),
  CONSTRAINT uk_payment_cancel_saga_id UNIQUE (saga_id),
  CONSTRAINT fk_payment_cancel_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id) ON DELETE RESTRICT,
  INDEX idx_payment_cancel_order_item (order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제 취소 (전액 · 부분)';
