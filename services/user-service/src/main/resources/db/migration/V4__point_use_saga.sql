-- userdb V4 — 포인트 사용 · 사용 취소의 sagaId 표식. 담당 perish95
-- 사용 취소(Saga 보상)가 사용보다 먼저 오면(order 가 타임아웃으로 포기) RELEASED 를 남겨, 늦게 도착한 사용을 409 로 막는다.
-- 사용도 USED 를 같은 트랜잭션에서 넣어, 같은 sagaId 의 사용 · 취소가 겹치면 uk 충돌로 한쪽만 표식을 갖는다 (product 재고 해제 표식과 같은 방식).
-- INSERT 전용 로그성 테이블 — created_at 만 (컨벤션 3-5), 구조는 processed_event 와 같게(대리키 PK + 자연키 uk)
CREATE TABLE point_use_saga (
  point_use_saga_id  BIGINT       NOT NULL AUTO_INCREMENT,
  saga_id            VARCHAR(64)  NOT NULL COMMENT 'order 주문 Saga ID — point_history.ref_key(USE · 보상 RESTORE)와 같은 값',
  status             VARCHAR(20)  NOT NULL COMMENT 'USED(사용 처리됨) / RELEASED(사용 전에 취소됨 — 이후 사용 거절)',
  created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_point_use_saga PRIMARY KEY (point_use_saga_id),
  CONSTRAINT uk_point_use_saga_saga_id UNIQUE (saga_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='포인트 사용 sagaId 표식 (늦게 도착한 사용 차단)';
