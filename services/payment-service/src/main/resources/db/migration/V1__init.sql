-- paymentdb V1 — payment-service (담당 구지훈)
-- 근거: 요구사항 PAY-01~04 · 결제 상태 · 07장 포트원 V2 · paymentdb 예시 DDL · docs/api/payment.md · 컨벤션 3장
-- 컬럼 순서: PK → 같은 서비스 FK → 다른 서비스 ID → 비즈니스 → 상태 → 도메인 시간 → 감사 컬럼 → version (컨벤션 3-9)
-- payment 는 이벤트를 발행 · 구독하지 않으므로 processed_event 없음. 결제 대기 만료는 order 스케줄러 담당이라 shedlock 없음.
-- 머지된 파일은 수정 금지 — 바꿀 때는 V2 부터.

-- 결제 — 주문 1건 = 결제 1건. PAID 판정은 포트원 조회 결과로만 (브라우저 응답 · 웹훅 본문을 믿지 않음)
CREATE TABLE payment (
  payment_id         BIGINT       NOT NULL AUTO_INCREMENT,
  order_id           BIGINT       NOT NULL COMMENT 'order-service orders.order_id (논리 참조)',
  order_no           VARCHAR(20)  NOT NULL COMMENT 'order-service orders.order_no (논리 참조) — 준비 · 검증 · 조회 멱등 키',
  portone_payment_id VARCHAR(64)  NOT NULL COMMENT '포트원 paymentId = pay-{orderNo} — 우리가 만들어 결제창에 넘기는 값',
  amount             BIGINT       NOT NULL COMMENT '기대 금액 = 실결제금액 (원, 1원 이상) — 포트원 조회 결과와 비교',
  cancelled_amount   BIGINT       NOT NULL DEFAULT 0 COMMENT '누적 취소 금액 (원) — 부분 취소 합계, amount 와 같아지면 CANCELLED',
  pg_tx_id           VARCHAR(100)          COMMENT '포트원 응답의 PG 거래 ID',
  method             VARCHAR(30)           COMMENT 'CARD / EASY_PAY ... (포트원 method.type 변환)',
  status             VARCHAR(20)  NOT NULL DEFAULT 'READY' COMMENT 'READY / PAID / PARTIAL_CANCELLED / CANCELLED / FAILED',
  paid_at            DATETIME(6)           COMMENT '결제 완료 시각 (포트원 paidAt)',
  cancelled_at       DATETIME(6)           COMMENT '전액 취소 시각',
  created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  version            BIGINT       NOT NULL DEFAULT 0 COMMENT '낙관적 락(@Version) — 확인 요청과 웹훅이 동시에 와도 한 번만 반영',
  CONSTRAINT pk_payment PRIMARY KEY (payment_id),
  CONSTRAINT uk_payment_order_no UNIQUE (order_no),                      -- 같은 주문 결제 준비 재요청은 기존 행 반환
  CONSTRAINT uk_payment_portone_payment_id UNIQUE (portone_payment_id),  -- 웹훅 data.paymentId 로 조회
  INDEX idx_payment_order (order_id)                                     -- order 쪽 ID 로 조회 (다른 서비스 ID — 수동 인덱스, 컨벤션 3-8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제 (포트원 V2)';

-- 결제 취소 (INSERT 전용 — created_at만). 포트원 취소 성공 1건 = 1행, 금액 불일치 자동 취소는 saga_id NULL
CREATE TABLE payment_cancel (
  payment_cancel_id       BIGINT       NOT NULL AUTO_INCREMENT,
  payment_id              BIGINT       NOT NULL,
  order_item_id           BIGINT                COMMENT 'order-service order_item.order_item_id (논리 참조) — 전액 취소면 NULL',
  saga_id                 CHAR(36)              COMMENT 'order-service saga_instance.saga_id (논리 참조) — 같은 취소 재요청 멱등 키',
  cancel_amount           BIGINT       NOT NULL COMMENT '취소 금액 (원)',
  reason                  VARCHAR(200) NOT NULL COMMENT '취소 사유 예: 구매자 취소 / 반품 / 결제 금액 불일치 자동 취소 / 주문 처리 실패',
  portone_cancellation_id VARCHAR(64)           COMMENT '포트원 취소 응답의 cancellation id',
  cancelled_at            DATETIME(6)  NOT NULL COMMENT '포트원 취소 완료 시각 (애플리케이션이 기록)',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_payment_cancel PRIMARY KEY (payment_cancel_id),
  CONSTRAINT uk_payment_cancel_saga_id UNIQUE (saga_id),  -- 클레임 1건 = 취소 Saga 1건 — 같은 취소 두 번 방지 (NULL 은 여러 건 허용)
  CONSTRAINT fk_payment_cancel_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id) ON DELETE RESTRICT,
  INDEX idx_payment_cancel_order_item (order_item_id)     -- 상품주문별 환불 내역 조회 (다른 서비스 ID — 수동 인덱스, 컨벤션 3-8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제 취소 (전액 · 부분)';
