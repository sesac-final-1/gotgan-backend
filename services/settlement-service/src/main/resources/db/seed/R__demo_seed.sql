-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션). 담당 jihoon-devstu
-- ⚠️ 생성 파일 — 직접 고치지 말고 gotgan-backend scripts/seed-gen/scenario.mjs 를 고친 뒤 `node scripts/seed-gen/gen.mjs` (구지훈)
-- 서비스끼리 같은 번호 · 금액 · 날짜를 가리킨다(store_id · user_id · order_item_id · 주문번호). 날짜는 실행일 기준 상대값(KST)
-- 정산 = order 시드의 구매확정 상품주문(order_item_id · 금액 같음)을 스토어 × 주차로 묶어 서버 규칙으로 계산
--   수수료 = 상품주문마다 내림(상위 카테고리율 · 결제 2.2%), 지난주 = 지급예정(정지 스토어는 자동 보류), 지지난주 = 지급 완료 + 지급 기록
-- settlement_store = store 시드 스냅샷(StoreApproved 로 받는 값 — 스토어명 · 판매자 이메일 · 계좌 · 상태)
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 바뀐 값은 유지

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);            -- KST 오늘 (DB 시간대와 무관)
SET @w1    := @today - INTERVAL (WEEKDAY(@today) + 7) DAY;        -- 지난주 월요일

-- 상위 카테고리 판매수수료율 — 상위 ID 1~6 은 product 시드와 같은 번호, effective_from 은 시드 구매확정일보다 앞서야 함
INSERT IGNORE INTO category_commission (category_id, commission_rate, effective_from) VALUES
  (1, 7.00, '2026-01-01'),
  (2, 9.00, '2026-01-01'),
  (3, 11.00, '2026-01-01'),
  (4, 11.00, '2026-01-01'),
  (5, 10.00, '2026-01-01'),
  (6, 6.00, '2026-01-01');

INSERT IGNORE INTO settlement_store (settlement_store_id, store_id, store_name, seller_email, bank_name, account_number, account_holder, store_status) VALUES
  (1, 1, '산골농부 박씨네', 'wlsgksvheh+seller1@gmail.com', '곳간은행', '100-0000-000001', '박영철', 'ACTIVE'),
  (2, 2, '토담공방', 'wlsgksvheh+seller2@gmail.com', '곳간은행', '100-0000-000002', '이수아', 'ACTIVE'),
  (11, 11, '오롯이 리넨', 'hello@orotlinen.example.com', '곳간은행', '100-0000-000011', '한지우', 'ACTIVE'),
  (12, 12, '무드앤로우', 'cs@moodrow.example.com', '곳간은행', '100-0000-000012', '오세훈', 'ACTIVE'),
  (13, 13, '걸음상회', 'georum@georum.example.com', '곳간은행', '100-0000-000013', '최민재', 'ACTIVE'),
  (15, 15, '볕드는집', 'house@byeot.example.com', '곳간은행', '100-0000-000015', '정다은', 'SUSPENDED'),
  (17, 17, '로스터리 하루', 'roast@haru.example.com', '곳간은행', '100-0000-000017', '윤하루', 'ACTIVE'),
  (18, 18, '피부정원', 'garden@skingarden.example.com', '곳간은행', '100-0000-000018', '강예린', 'ACTIVE'),
  (19, 19, '데스크테리어랩', 'lab@deskterior.example.com', '곳간은행', '100-0000-000019', '서준호', 'ACTIVE');

INSERT IGNORE INTO settlement (settlement_id, store_id, week_start, week_end, item_count, carried_over_count, sales_amt, commission_amt, payment_fee_amt, payout_amt, status, hold_reason, scheduled_pay_date, held_at, paid_at, created_at) VALUES
  (1, 1, @w1 - INTERVAL 0 DAY, @w1 - INTERVAL 0 DAY + INTERVAL 6 DAY, 2, 0, 74980, 5248, 1649, 68083, 'SCHEDULED', NULL, @w1 - INTERVAL 0 DAY + INTERVAL 10 DAY, NULL, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (2, 1, @w1 - INTERVAL 7 DAY, @w1 - INTERVAL 7 DAY + INTERVAL 6 DAY, 1, 0, 53960, 3777, 1187, 48996, 'PAID', NULL, @w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (3, 2, @w1 - INTERVAL 0 DAY, @w1 - INTERVAL 0 DAY + INTERVAL 6 DAY, 1, 0, 48600, 4374, 1069, 43157, 'SCHEDULED', NULL, @w1 - INTERVAL 0 DAY + INTERVAL 10 DAY, NULL, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (4, 2, @w1 - INTERVAL 7 DAY, @w1 - INTERVAL 7 DAY + INTERVAL 6 DAY, 1, 0, 56000, 5040, 1232, 49728, 'PAID', NULL, @w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (5, 11, @w1 - INTERVAL 7 DAY, @w1 - INTERVAL 7 DAY + INTERVAL 6 DAY, 1, 0, 75650, 8321, 1664, 65665, 'PAID', NULL, @w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (6, 15, @w1 - INTERVAL 0 DAY, @w1 - INTERVAL 0 DAY + INTERVAL 6 DAY, 1, 0, 101150, 9103, 2225, 89822, 'HELD', '스토어 정지 · 폐점 — 자동 보류', @w1 - INTERVAL 0 DAY + INTERVAL 10 DAY, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00'), NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (7, 17, @w1 - INTERVAL 0 DAY, @w1 - INTERVAL 0 DAY + INTERVAL 6 DAY, 1, 0, 36000, 2520, 792, 32688, 'SCHEDULED', NULL, @w1 - INTERVAL 0 DAY + INTERVAL 10 DAY, NULL, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00'));

INSERT IGNORE INTO settlement_item (settlement_item_id, settlement_id, order_item_id, order_no, store_id, product_id, product_name, category_id, item_amt, commission_rate, commission_amt, payment_fee_amt, confirmed_at) VALUES
  (1004, 1, 1004, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901004'), 1, 102, '지리산 야생화 꿀 1.2kg', 1, 48000, 7.00, 3360, 1056, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00')),
  (1005, 1, 1005, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901005'), 1, 101, '서귀포 노지 햇감귤 5kg', 1, 26980, 7.00, 1888, 593, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00')),
  (1001, 2, 1001, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901001'), 1, 101, '서귀포 노지 햇감귤 5kg', 1, 53960, 7.00, 3777, 1187, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00')),
  (1006, 3, 1006, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901006'), 2, 202, '백자 림 접시 3종 세트', 2, 48600, 9.00, 4374, 1069, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '10:00:00')),
  (1002, 4, 1002, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901002'), 2, 201, '분청 손잡이 머그 350ml', 2, 56000, 9.00, 5040, 1232, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00')),
  (1003, 5, 1003, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901003'), 11, 1101, '프렌치 리넨 오버핏 셔츠', 3, 75650, 11.00, 8321, 1664, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00')),
  (1008, 6, 1008, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901008'), 15, 1501, '오크 베이스 패브릭 테이블 램프', 2, 101150, 9.00, 9103, 2225, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00')),
  (1007, 7, 1007, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 4 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901007'), 17, 1701, '에티오피아 예가체프 G1 원두 200g', 1, 36000, 7.00, 2520, 792, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 4 DAY, '10:00:00'));

INSERT IGNORE INTO payout (payout_id, settlement_id, payout_amt, bank_name, account_number, account_holder, paid_at) VALUES
  (2, 2, 48996, '곳간은행', '100-0000-000001', '박영철', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')),
  (4, 4, 49728, '곳간은행', '100-0000-000002', '이수아', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')),
  (5, 5, 65665, '곳간은행', '100-0000-000011', '한지우', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00'));

INSERT IGNORE INTO settlement_history (settlement_history_id, settlement_id, from_status, to_status, actor_type, reason, created_at) VALUES
  (11, 1, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (21, 2, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (22, 2, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')),
  (31, 3, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (41, 4, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (42, 4, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')),
  (51, 5, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 7 DAY, '03:00:00')),
  (52, 5, 'SCHEDULED', 'PAID', 'ADMIN', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 10 DAY, '10:00:00')),
  (61, 6, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (62, 6, 'SCHEDULED', 'HELD', 'SYSTEM', '스토어 정지 · 폐점 — 자동 보류', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00')),
  (71, 7, NULL, 'SCHEDULED', 'SYSTEM', '주간 집계', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 7 DAY, '03:00:00'));
