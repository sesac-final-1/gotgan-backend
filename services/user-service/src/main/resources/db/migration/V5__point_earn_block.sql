-- userdb V5 — 적립 제외 상품주문 표식. 담당 perish95
-- 취소 · 반품 환원 · 회수(POST /internal/points/reversals)를 처리할 때 그 상품주문을 남겨, 뒤늦게 도착한 OrderPaid 적립을 건너뛴다.
-- 취소가 적립보다 먼저 오면 회수할 적립이 없어 이력이 남지 않기 때문에 따로 둔다. 적립 · 회수 모두 회원 행을 잠그고 처리해 순서가 엇갈려도 한쪽만 반영된다.
-- INSERT 전용 로그성 테이블 — created_at 만 (컨벤션 3-5), 구조는 point_use_saga 와 같게(대리키 PK + 자연키 uk)
CREATE TABLE point_earn_block (
  point_earn_block_id  BIGINT       NOT NULL AUTO_INCREMENT,
  order_item_id        BIGINT       NOT NULL COMMENT 'order 상품주문 ID — 취소 · 반품돼 적립하지 않는다',
  created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_point_earn_block PRIMARY KEY (point_earn_block_id),
  CONSTRAINT uk_point_earn_block_order_item_id UNIQUE (order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='적립 제외 상품주문 (취소 · 반품 뒤 늦게 도착한 적립 차단)';
