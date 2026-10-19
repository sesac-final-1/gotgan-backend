-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션)
-- 목업(gotgan_expage/src/data/*.js) 데이터를 INSERT 로 옮긴다. 담당 jihoon-devstu
-- 생성: 금액은 FeeCalculator 규칙(판매수수료 = 금액 × 율%, 결제수수료 2.2%, 원 단위 내림)으로 계산한 값
--
-- 시연 판매자 스토어 2개 — store 시드(store_id 1 · 2) · userdb 시드(user_id 2 · 3)와 번호를 맞춘다
--   store 시드는 StoreApproved 를 발행하지 않으므로 정산용 스토어 스냅샷을 여기서 직접 넣는다
--   판매자 이메일 = 정산 명세서 메일 수신 주소 — Gmail + 별칭(같은 받은편지함), SES 샌드박스 수신자로 인증(gotgan-config ses_recipient_emails)
-- 지난 4주 정산 — 스토어별 지난주 1건 지급예정(SCHEDULED) + 그 전 3주 지급완료(PAID) · 상품주문 2건씩 · 지급 기록 · 상태 이력
--   날짜는 시드 실행일(KST) 기준 상대값 — 클러스터는 매일 빈 DB 로 다시 만들어지므로 "지난주"가 항상 실제 지난주
--   상품주문 · 상품 ID 는 order · product 시드와 겹치지 않게 900000 대 (정산 상세 표시용 스냅샷, 다른 서비스 행은 없음)
--   판매수수료율 7.00(category 1) · 9.00(category 2) 은 정산 당시 스냅샷 — category_commission 은 상위 카테고리 ID 확정(product 시드) 후 추가
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE)

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);            -- KST 오늘 (DB 시간대와 무관)
SET @w1    := @today - INTERVAL (WEEKDAY(@today) + 7) DAY;        -- 지난주 월요일

INSERT IGNORE INTO settlement_store (settlement_store_id, store_id, store_name, seller_email, bank_name, account_number, account_holder, store_status)
VALUES
  (1, 1, '곳간상회', 'wlsgksvheh+seller1@gmail.com', '곳간은행', '100-0000-000001', '판매자1', 'ACTIVE'),
  (2, 2, '곳간리빙', 'wlsgksvheh+seller2@gmail.com', '곳간은행', '100-0000-000002', '(주)곳간리빙', 'ACTIVE');

INSERT IGNORE INTO settlement
  (settlement_id, store_id, week_start, week_end, item_count, sales_amt, commission_amt, payment_fee_amt, payout_amt, status, scheduled_pay_date, paid_at, created_at)
VALUES
  (1, 1, @w1 - INTERVAL 0 DAY, @w1 - INTERVAL 0 DAY + INTERVAL 6 DAY, 2, 82000, 5740, 1804, 74456, 'SCHEDULED', @w1 - INTERVAL 0 DAY + INTERVAL 10 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (2, 1, @w1 - INTERVAL 7 DAY, @w1 - INTERVAL 7 DAY + INTERVAL 6 DAY, 2, 79000, 5530, 1738, 71732, 'PAID', @w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (3, 1, @w1 - INTERVAL 14 DAY, @w1 - INTERVAL 14 DAY + INTERVAL 6 DAY, 2, 82000, 5740, 1804, 74456, 'PAID', @w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 7 DAY, '03:00:00')),
  (4, 1, @w1 - INTERVAL 21 DAY, @w1 - INTERVAL 21 DAY + INTERVAL 6 DAY, 2, 57000, 3990, 1254, 51756, 'PAID', @w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 7 DAY, '03:00:00')),
  (5, 2, @w1 - INTERVAL 0 DAY, @w1 - INTERVAL 0 DAY + INTERVAL 6 DAY, 2, 43000, 3870, 946, 38184, 'SCHEDULED', @w1 - INTERVAL 0 DAY + INTERVAL 10 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (6, 2, @w1 - INTERVAL 7 DAY, @w1 - INTERVAL 7 DAY + INTERVAL 6 DAY, 2, 93000, 8370, 2046, 82584, 'PAID', @w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (7, 2, @w1 - INTERVAL 14 DAY, @w1 - INTERVAL 14 DAY + INTERVAL 6 DAY, 2, 65000, 5850, 1430, 57720, 'PAID', @w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 7 DAY, '03:00:00')),
  (8, 2, @w1 - INTERVAL 21 DAY, @w1 - INTERVAL 21 DAY + INTERVAL 6 DAY, 2, 31000, 2790, 682, 27528, 'PAID', @w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 7 DAY, '03:00:00'));

INSERT IGNORE INTO settlement_item
  (settlement_item_id, settlement_id, order_item_id, order_no, store_id, product_id, product_name, category_id, item_amt, commission_rate, commission_amt, payment_fee_amt, confirmed_at)
VALUES
  (1, 1, 900001, 'DEMO-900001', 1, 900101, '제철 사과 3kg', 1, 50000, 7.00, 3500, 1100, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00')),
  (2, 1, 900002, 'DEMO-900002', 1, 900102, '햇배 5kg', 1, 32000, 7.00, 2240, 704, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '15:30:00')),
  (3, 2, 900003, 'DEMO-900003', 1, 900103, '감귤 5kg', 1, 54000, 7.00, 3780, 1188, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00')),
  (4, 2, 900004, 'DEMO-900004', 1, 900101, '제철 사과 3kg', 1, 25000, 7.00, 1750, 550, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '15:30:00')),
  (5, 3, 900005, 'DEMO-900005', 1, 900102, '햇배 5kg', 1, 64000, 7.00, 4480, 1408, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 1 DAY, '10:00:00')),
  (6, 3, 900006, 'DEMO-900006', 1, 900103, '감귤 5kg', 1, 18000, 7.00, 1260, 396, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 3 DAY, '15:30:00')),
  (7, 4, 900007, 'DEMO-900007', 1, 900101, '제철 사과 3kg', 1, 25000, 7.00, 1750, 550, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 1 DAY, '10:00:00')),
  (8, 4, 900008, 'DEMO-900008', 1, 900102, '햇배 5kg', 1, 32000, 7.00, 2240, 704, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 3 DAY, '15:30:00')),
  (9, 5, 900009, 'DEMO-900009', 2, 900201, '세라믹 머그컵', 2, 24000, 9.00, 2160, 528, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00')),
  (10, 5, 900010, 'DEMO-900010', 2, 900202, '린넨 앞치마', 2, 19000, 9.00, 1710, 418, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '15:30:00')),
  (11, 6, 900011, 'DEMO-900011', 2, 900203, '원목 도마', 2, 81000, 9.00, 7290, 1782, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00')),
  (12, 6, 900012, 'DEMO-900012', 2, 900201, '세라믹 머그컵', 2, 12000, 9.00, 1080, 264, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '15:30:00')),
  (13, 7, 900013, 'DEMO-900013', 2, 900202, '린넨 앞치마', 2, 38000, 9.00, 3420, 836, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 1 DAY, '10:00:00')),
  (14, 7, 900014, 'DEMO-900014', 2, 900203, '원목 도마', 2, 27000, 9.00, 2430, 594, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 3 DAY, '15:30:00')),
  (15, 8, 900015, 'DEMO-900015', 2, 900201, '세라믹 머그컵', 2, 12000, 9.00, 1080, 264, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 1 DAY, '10:00:00')),
  (16, 8, 900016, 'DEMO-900016', 2, 900202, '린넨 앞치마', 2, 19000, 9.00, 1710, 418, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 3 DAY, '15:30:00'));

INSERT IGNORE INTO payout (settlement_id, payout_amt, bank_name, account_number, account_holder, paid_at)
VALUES
  (2, 71732, '곳간은행', '100-0000-000001', '판매자1', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')),
  (3, 74456, '곳간은행', '100-0000-000001', '판매자1', TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, '10:00:00')),
  (4, 51756, '곳간은행', '100-0000-000001', '판매자1', TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, '10:00:00')),
  (6, 82584, '곳간은행', '100-0000-000002', '(주)곳간리빙', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')),
  (7, 57720, '곳간은행', '100-0000-000002', '(주)곳간리빙', TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, '10:00:00')),
  (8, 27528, '곳간은행', '100-0000-000002', '(주)곳간리빙', TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, '10:00:00'));

-- 이력은 PK 를 지정하지 않으므로 정산서에 이력이 없을 때만 넣는다 (재실행 시 중복 방지)
INSERT INTO settlement_history (settlement_id, from_status, to_status, actor_type, reason, created_at)
SELECT * FROM (
  SELECT 1 AS settlement_id, NULL AS from_status, 'SCHEDULED' AS to_status, 'SYSTEM' AS actor_type, '주간 집계' AS reason, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00') AS created_at
  UNION ALL SELECT 2, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')
  UNION ALL SELECT 2, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')
  UNION ALL SELECT 3, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 7 DAY, '03:00:00')
  UNION ALL SELECT 3, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, '10:00:00')
  UNION ALL SELECT 4, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 7 DAY, '03:00:00')
  UNION ALL SELECT 4, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, '10:00:00')
  UNION ALL SELECT 5, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')
  UNION ALL SELECT 6, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')
  UNION ALL SELECT 6, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')
  UNION ALL SELECT 7, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 7 DAY, '03:00:00')
  UNION ALL SELECT 7, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 14 DAY + INTERVAL 10 DAY, '10:00:00')
  UNION ALL SELECT 8, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 7 DAY, '03:00:00')
  UNION ALL SELECT 8, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 21 DAY + INTERVAL 10 DAY, '10:00:00')
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM settlement_history h WHERE h.settlement_id BETWEEN 1 AND 8);
