-- settlementdb V5 — settlement-service (담당 구지훈)
-- 근거: events.md SettlementPaid payload(settlementId · storeId · week · payoutAmt · sellerEmail) · StoreApproved payload(sellerEmail)
-- 정산 명세서 메일(Lambda settlement-report)의 수신자 — settlement 는 store 를 동기 호출하지 않으므로 StoreApproved 에서 받아 둔다.

ALTER TABLE settlement_store
  ADD COLUMN seller_email VARCHAR(100) COMMENT 'StoreApproved 스냅샷 — 판매자 이메일 (SettlementPaid 명세서 메일 수신자)' AFTER store_name;
