-- orderdb V2 — 주문 · 상품주문 · 클레임 · Saga · Outbox
-- 근거: 가이드라인 06장 orderdb 예시 · 컨벤션 3장 · gotgan-config docs/events.md (PR #22 OrderItemConfirmed payload)
-- cart_item · shedlock 은 V1 에 있음 (V1 수정 금지)
-- 컬럼 순서: PK → 같은 서비스 FK → 다른 서비스 ID → 비즈니스 → 상태 → 도메인 시간 → 감사 → version (컨벤션 3-9)

-- 주문 = 결제 단위 (한 번 결제에 여러 스토어 상품이 섞일 수 있음)
CREATE TABLE orders (
  order_id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '주문 PK',
  buyer_id             BIGINT       NOT NULL COMMENT 'user-service users.user_id (논리 참조)',
  order_no             VARCHAR(20)  NOT NULL COMMENT '외부 노출 주문번호 예: 20261021-000123',
  orderer_name         VARCHAR(50)  NOT NULL COMMENT '주문 시점 스냅샷 (실명 users.name) — 주문 확인 메일 등 알림용, 화면 공개 금지',
  orderer_display_name VARCHAR(50)  NOT NULL COMMENT '주문 시점 스냅샷 (공개 표시용 이름) — users.nick 이 있으면 nick, NULL · 공백이면 users.name 을 마스킹한 값(예: 홍*동). 주문 생성 시 애플리케이션이 결정. OrderItemConfirmed.buyerDisplayName 으로 review 에 전달',
  orderer_email        VARCHAR(100) NOT NULL COMMENT '주문 시점 스냅샷 — 알림 Lambda가 user-service를 부르지 않게',
  receiver_name        VARCHAR(50)  NOT NULL COMMENT '수령인',
  receiver_phone       VARCHAR(20)  NOT NULL COMMENT '수령인 연락처',
  zipcode              VARCHAR(10)  NOT NULL COMMENT '우편번호',
  address1             VARCHAR(200) NOT NULL COMMENT '기본 주소',
  address2             VARCHAR(200)          COMMENT '상세 주소',
  delivery_memo        VARCHAR(200)          COMMENT '배송 요청사항',
  total_product_amt    BIGINT       NOT NULL COMMENT '상품 금액 합계 (원)',
  total_shipping_fee   BIGINT       NOT NULL COMMENT '배송비 합계 (원)',
  used_point           BIGINT       NOT NULL DEFAULT 0 COMMENT '사용 포인트 합계 (원) — user-service 차감은 Saga 2단계',
  pay_amt              BIGINT       NOT NULL COMMENT '실결제금액 (원) = 상품 + 배송비 - 사용 포인트, 포트원 결제창 금액',
  status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PAID / FAILED / CANCELLED',
  paid_at              DATETIME(6)           COMMENT '결제 완료 시각 (애플리케이션이 기록)',
  created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  version              BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA @Version 낙관적 락',
  CONSTRAINT pk_orders PRIMARY KEY (order_id),
  CONSTRAINT uk_orders_order_no UNIQUE (order_no),
  INDEX idx_orders_buyer_created (buyer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 (결제 단위)';

-- 스토어별 묶음배송비 (같은 스토어 상품끼리 배송비 1회)
CREATE TABLE order_store_shipment (
  order_store_shipment_id BIGINT      NOT NULL AUTO_INCREMENT COMMENT '스토어별 배송비 PK',
  order_id                BIGINT      NOT NULL COMMENT 'orders.order_id',
  store_id                BIGINT      NOT NULL COMMENT 'store-service store.store_id (논리 참조)',
  shipping_fee            BIGINT      NOT NULL COMMENT '스토어 묶음 배송비 (원)',
  created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  CONSTRAINT pk_order_store_shipment PRIMARY KEY (order_store_shipment_id),
  CONSTRAINT uk_order_store_shipment_order_store UNIQUE (order_id, store_id),
  CONSTRAINT fk_order_store_shipment_orders FOREIGN KEY (order_id) REFERENCES orders (order_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문의 스토어별 배송비';

-- 상품주문 = 상태 관리 단위
CREATE TABLE order_item (
  order_item_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '상품주문 PK',
  order_id          BIGINT       NOT NULL COMMENT 'orders.order_id',
  store_id          BIGINT       NOT NULL COMMENT 'store-service store.store_id (논리 참조) — 판매자 주문 조회 핵심 키',
  product_id        BIGINT       NOT NULL COMMENT 'product-service product.product_id (논리 참조)',
  product_option_id BIGINT       NOT NULL COMMENT 'product-service product_option.product_option_id (논리 참조)',
  category_id       BIGINT       NOT NULL COMMENT 'product-service category.category_id (논리 참조) — 하위 카테고리 스냅샷 (OrderItemConfirmed.categoryId)',
  top_category_id   BIGINT       NOT NULL COMMENT 'product-service category.category_id (논리 참조) — 상위 카테고리 스냅샷, 판매수수료 기준 (OrderItemConfirmed.topCategoryId, events.md 10/16)',
  item_no           VARCHAR(24)  NOT NULL COMMENT '외부 노출 상품주문번호 예: 20261021-000123-01',
  store_name        VARCHAR(100) NOT NULL COMMENT '주문 시점 스냅샷',
  product_name      VARCHAR(200) NOT NULL COMMENT '주문 시점 스냅샷 (OrderItemConfirmed.productName)',
  option_name       VARCHAR(200)          COMMENT '주문 시점 스냅샷 예: 오트밀 / M',
  thumbnail_url     VARCHAR(500)          COMMENT '주문 시점 스냅샷 — 주문 목록 썸네일',
  unit_price        BIGINT       NOT NULL COMMENT '단가 (원)',
  quantity          INT UNSIGNED NOT NULL COMMENT '수량',
  item_amt          BIGINT       NOT NULL COMMENT '상품주문 금액 (원)',
  used_point        BIGINT       NOT NULL DEFAULT 0 COMMENT '금액 비율로 배분한 사용 포인트 (원) — 취소 · 반품 시 환원액',
  earned_point      BIGINT       NOT NULL DEFAULT 0 COMMENT '적립 포인트 (원) = (item_amt - used_point)의 5% — 취소 · 반품 시 회수액',
  courier           VARCHAR(30)           COMMENT '택배사',
  tracking_no       VARCHAR(40)           COMMENT '송장번호',
  status            VARCHAR(30)  NOT NULL DEFAULT 'PAYMENT_DONE' COMMENT 'PAYMENT_DONE / PREPARING / SHIPPING / DELIVERED / CONFIRMED / CANCEL_REQUESTED / CANCELLED / RETURN_REQUESTED / RETURNED / EXCHANGE_REQUESTED',
  shipped_at        DATETIME(6)           COMMENT '발송 시각',
  delivered_at      DATETIME(6)           COMMENT '배송 완료 시각',
  confirmed_at      DATETIME(6)           COMMENT '구매확정 시각 — 정산 기준일',
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  version           BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA @Version 낙관적 락',
  CONSTRAINT pk_order_item PRIMARY KEY (order_item_id),
  CONSTRAINT uk_order_item_item_no UNIQUE (item_no),
  CONSTRAINT fk_order_item_orders FOREIGN KEY (order_id) REFERENCES orders (order_id) ON DELETE RESTRICT,
  INDEX idx_order_item_store_status (store_id, status, created_at),   -- 판매자 주문관리 탭
  INDEX idx_order_item_status_delivered (status, delivered_at),       -- 자동 구매확정 스케줄러
  INDEX idx_order_item_product (product_id)                           -- 다른 서비스 ID 수동 인덱스
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품주문 (상태 관리 단위)';

-- 상태 변경 이력 — INSERT-only 라 created_at 만
CREATE TABLE order_item_history (
  order_item_history_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '상태 이력 PK',
  order_item_id         BIGINT       NOT NULL COMMENT 'order_item.order_item_id',
  actor_id              BIGINT                COMMENT 'user-service users.user_id (논리 참조), SYSTEM이면 NULL',
  from_status           VARCHAR(30)           COMMENT '변경 전 상태',
  to_status             VARCHAR(30)  NOT NULL COMMENT '변경 후 상태',
  actor_type            VARCHAR(10)  NOT NULL COMMENT 'BUYER / SELLER / ADMIN / SYSTEM',
  reason                VARCHAR(300)          COMMENT '사유',
  created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  CONSTRAINT pk_order_item_history PRIMARY KEY (order_item_history_id),
  CONSTRAINT fk_order_item_history_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (order_item_id) ON DELETE RESTRICT,
  INDEX idx_order_item_history_item_created (order_item_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품주문 상태 변경 이력';

-- 취소 · 반품 · 교환 (클레임) — 상품주문 status 와 한 트랜잭션으로 변경
CREATE TABLE order_claim (
  order_claim_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '클레임 PK',
  order_item_id  BIGINT       NOT NULL COMMENT 'order_item.order_item_id',
  requester_id   BIGINT       NOT NULL COMMENT 'user-service users.user_id (논리 참조) — 요청한 구매자',
  claim_type     VARCHAR(20)  NOT NULL COMMENT 'CANCEL / RETURN / EXCHANGE',
  reason_code    VARCHAR(30)  NOT NULL COMMENT 'CHANGE_OF_MIND / DEFECTIVE / WRONG_DELIVERY / DELAYED ...',
  reason_detail  VARCHAR(500)          COMMENT '상세 사유 (구매자 입력)',
  reject_reason  VARCHAR(500)          COMMENT '판매자 거부 사유',
  refund_amt     BIGINT                COMMENT '환불 금액 (원) — 교환이면 NULL (OrderItemCancelled · Returned.refundAmt)',
  status         VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED' COMMENT 'REQUESTED / APPROVED / REJECTED / COMPLETED',
  requested_at   DATETIME(6)  NOT NULL COMMENT '요청 시각 (애플리케이션이 기록)',
  processed_at   DATETIME(6)           COMMENT '승인 · 거부 · 완료 처리 시각',
  created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  CONSTRAINT pk_order_claim PRIMARY KEY (order_claim_id),
  CONSTRAINT fk_order_claim_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (order_item_id) ON DELETE RESTRICT,
  INDEX idx_order_claim_requester_requested (requester_id, requested_at),   -- 구매자 취소/반품 내역
  INDEX idx_order_claim_status_requested (status, requested_at)             -- 판매자 클레임 처리 대기
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='취소 · 반품 · 교환 요청';

-- Saga 진행 상태 (오케스트레이터의 기억)
CREATE TABLE saga_instance (
  saga_instance_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Saga PK',
  order_id         BIGINT       NOT NULL COMMENT 'orders.order_id',
  order_claim_id   BIGINT                COMMENT 'order_claim.order_claim_id — 취소 · 반품 Saga일 때만',
  saga_id          CHAR(36)     NOT NULL COMMENT 'UUID — Saga 참여자 API(user · product · payment) 멱등 키',
  saga_type        VARCHAR(30)  NOT NULL COMMENT 'PLACE_ORDER / CANCEL_ITEM / RETURN_ITEM',
  current_step     VARCHAR(30)  NOT NULL COMMENT 'ORDER_CREATED / POINT_USED / STOCK_RESERVED / PAYMENT_READY / PAYMENT_VERIFIED / DONE',
  last_error       VARCHAR(500)          COMMENT '마지막 실패 사유',
  status           VARCHAR(20)  NOT NULL DEFAULT 'STARTED' COMMENT 'STARTED / COMPENSATING / COMPLETED / COMPENSATED / FAILED',
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정 시각',
  CONSTRAINT pk_saga_instance PRIMARY KEY (saga_instance_id),
  CONSTRAINT uk_saga_instance_saga_id UNIQUE (saga_id),
  CONSTRAINT fk_saga_instance_orders FOREIGN KEY (order_id) REFERENCES orders (order_id) ON DELETE RESTRICT,
  CONSTRAINT fk_saga_instance_order_claim FOREIGN KEY (order_claim_id) REFERENCES order_claim (order_claim_id) ON DELETE RESTRICT,
  INDEX idx_saga_instance_status_updated (status, updated_at)   -- 결제 대기 만료 · 멈춘 Saga 복구 스케줄러
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Saga 진행 상태';

-- (Could) Transactional Outbox
CREATE TABLE outbox_event (
  outbox_event_id BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Outbox PK',
  event_id        VARCHAR(36) NOT NULL COMMENT '이벤트 봉투 eventId (UUID) — 소비자 processed_event 멱등 키',
  aggregate_type  VARCHAR(30) NOT NULL COMMENT 'ORDER / ORDER_ITEM',
  aggregate_id    VARCHAR(40) NOT NULL COMMENT '주문번호 또는 상품주문번호',
  event_type      VARCHAR(50) NOT NULL COMMENT 'OrderPaid / OrderItemShipped / OrderItemConfirmed / OrderItemCancelled / OrderItemReturned',
  payload         JSON        NOT NULL COMMENT '이벤트 payload (docs/events.md)',
  status          VARCHAR(10) NOT NULL DEFAULT 'NEW' COMMENT 'NEW / SENT',
  sent_at         DATETIME(6)          COMMENT 'SNS 발행 시각',
  created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  CONSTRAINT pk_outbox_event PRIMARY KEY (outbox_event_id),
  CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id),
  INDEX idx_outbox_event_status (status, outbox_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Transactional Outbox';