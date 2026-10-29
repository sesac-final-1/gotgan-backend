-- productdb V3 — product-service (담당 박지연)
-- 근거: StoreStatusChanged 소비(PRD-12) — 스토어 상태를 상품 행(is_store_active)에만 두면 상품이 없을 때 온 이벤트를 기억할 곳이 없어,
--   정지 · 폐점 뒤 새로 등록한 상품이 운영 중으로 시작해 노출된다. 스토어별 마지막 상태를 따로 두고 등록 때 초기값으로 쓴다.
-- 행은 StoreStatusChanged 를 처음 받을 때 생긴다 — 행이 없는 스토어는 운영 중(승인 직후 ACTIVE)으로 본다. 순서 규칙은 events.md A안.

CREATE TABLE store_status (
  store_id          BIGINT      NOT NULL COMMENT '스토어 ID (storedb store.store_id, 논리 참조)',
  status            VARCHAR(20) NOT NULL COMMENT '마지막으로 반영한 StoreStatusChanged.status — ACTIVE 만 운영 중, 그 밖의 값(SUSPENDED · CLOSED …)은 상품 숨김',
  status_changed_at DATETIME(6) NOT NULL COMMENT '마지막으로 반영한 이벤트 occurredAt — 이보다 오래된 이벤트는 무시 (SQS 순서 미보장)',
  created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT pk_store_status PRIMARY KEY (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='스토어 운영 상태 스냅샷 — StoreStatusChanged 로 갱신, 상품 등록 때 is_store_active 초기값';
