-- orderdb V7 — 주문번호 채번 (담당 이경석, 11/5)
-- 그날 마지막 번호를 orders 에서 읽어 +1 하면 동시 주문이 같은 번호를 만들어 충돌한다(부하 테스트 경-1) — 날짜당 한 줄을 원자적으로 올린다.
-- 이미 쓴 번호와 겹치지 않게 기존 주문의 날짜별 최대 순번에서 시작한다.

CREATE TABLE order_no_sequence (
  order_date DATE   NOT NULL COMMENT '주문번호의 날짜',
  last_no    BIGINT NOT NULL COMMENT '그날 마지막으로 발급한 순번',
  CONSTRAINT pk_order_no_sequence PRIMARY KEY (order_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문번호 날짜별 채번';

INSERT INTO order_no_sequence (order_date, last_no)
SELECT STR_TO_DATE(ymd, '%Y%m%d'), MAX(seq)
FROM (SELECT LEFT(order_no, 8) AS ymd, CAST(SUBSTRING(order_no, 10) AS UNSIGNED) AS seq FROM orders) issued
GROUP BY ymd;
