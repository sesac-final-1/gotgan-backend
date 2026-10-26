-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션). 담당 perish95
-- ⚠️ 생성 파일 — 직접 고치지 말고 gotgan-backend scripts/seed-gen/scenario.mjs 를 고친 뒤 `node scripts/seed-gen/gen.mjs` (구지훈)
-- 서비스끼리 같은 번호 · 금액 · 날짜를 가리킨다(store_id · user_id · order_item_id · 주문번호). 날짜는 실행일 기준 상대값(KST)
-- 1 관리자 · 2 · 3 판매자 · 4~6 구매자 = Keycloak 시드 계정(이메일 · user_id 고정 — gotgan-config scripts/keycloak-seed.sh). keycloak_id 는 NULL(이메일로 연결)
-- 4~6 은 목업 u1~u3 프로필, 포인트 · 누적 구매확정 금액은 주문 시드에서 계산(잔액 = 적립 이력 합)
-- 104~114 목업 구매자 · 201~ 스토어 대표자 = 조회용(Keycloak 계정 없음 — 로그인 불가). 111 은 이용정지(관리자 회원 화면 시연)
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 바뀐 값은 유지

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);            -- KST 오늘 (DB 시간대와 무관)
SET @w1    := @today - INTERVAL (WEEKDAY(@today) + 7) DAY;        -- 지난주 월요일

-- 시연 계정 — 프로필은 시드 값으로 되돌리고 포인트 · 상태는 시연 중 바뀐 값 유지
INSERT INTO users (user_id, email, name, nick, phone, signup_provider, grade, status)
VALUES
  (1, 'wlsgksvheh+admin@gmail.com',   '곳간 관리자', '관리자', '010-0000-0001', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (2, 'wlsgksvheh+seller1@gmail.com', '박영철', NULL, '064-0000-0006', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (3, 'wlsgksvheh+seller2@gmail.com', '이수아', NULL, '063-0000-0004', 'LOCAL', 'NORMAL', 'ACTIVE')
AS seed
ON DUPLICATE KEY UPDATE email = seed.email, name = seed.name, nick = seed.nick, phone = seed.phone;

INSERT IGNORE INTO users (user_id, email, name, nick, phone, signup_provider, point_balance, total_confirmed_amt, grade, status, status_reason, suspended_at, suspended_until, last_login_at, created_at) VALUES
  (4, 'wlsgksvheh+buyer1@gmail.com', '김서연', '서연이네', '010-0000-0010', 'LOCAL', 10650, 139000, 'GOLD', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 0 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 820 DAY, '10:00:00')),
  (5, 'wlsgksvheh+buyer2@gmail.com', '이도현', '도현', '010-0000-0011', 'LOCAL', 10500, 95000, 'GOLD', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 0 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 1400 DAY, '10:00:00')),
  (6, 'wlsgksvheh+buyer3@gmail.com', '박지민', '지민찡', '010-0000-0012', 'LOCAL', 11950, 152000, 'SILVER', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 2 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 400 DAY, '10:00:00')),
  (104, 'yuna.choi@example.com', '최유나', '유나', '010-0000-0013', 'LOCAL', 1200, 98000, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 5 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 60 DAY, '10:00:00')),
  (105, 'hajun.dad@example.com', '정하준', '하준아빠', '010-0000-0014', 'LOCAL', 8800, 1210000, 'GOLD', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 980 DAY, '10:00:00')),
  (106, 'chaewon.y@example.com', '윤채원', '채원', '010-0000-0015', 'LOCAL', 2050, 402000, 'SILVER', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 3 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 300 DAY, '10:00:00')),
  (107, 'minho.kang@example.com', '강민호', '민호', '010-0000-0016', 'LOCAL', 500, 32000, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 12 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (108, 'eunbi.cho@example.com', '조은비', '은비', '010-0000-0017', 'LOCAL', 61000, 7710000, 'GOLD', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 0 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 1650 DAY, '10:00:00')),
  (109, 'jaehyun.lim@example.com', '임재현', 'jh_lim', '010-0000-0018', 'LOCAL', 0, 140000, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 390 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 720 DAY, '10:00:00')),
  (110, 'sohee.han@example.com', '한소희', '소소', '010-0000-0019', 'LOCAL', 4100, 388000, 'SILVER', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 250 DAY, '10:00:00')),
  (111, 'junseo.oh@example.com', '오준서', '블랙컨슈머아님', '010-0000-0020', 'LOCAL', 0, 210000, 'NORMAL', 'SUSPENDED', '허위 리뷰 반복 작성', TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL -10 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 4 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 90 DAY, '10:00:00')),
  (112, 'dain.seo@example.com', '서다인', '다인', '010-0000-0021', 'LOCAL', 9900, 1530000, 'GOLD', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 0 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 1100 DAY, '10:00:00')),
  (113, 'taeyang.m@example.com', '문태양', '태양', '010-0000-0022', 'LOCAL', 3000, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 0 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 8 DAY, '10:00:00')),
  (114, 'subin.bae@example.com', '배수빈', '수빈', '010-0000-0023', 'LOCAL', 5200, 610000, 'SILVER', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 6 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 510 DAY, '10:00:00')),
  (201, 'seller11@seed.gotgan.example', '한지우', NULL, '02-0000-0001', 'LOCAL', 0, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 615 DAY, '10:00:00')),
  (202, 'seller12@seed.gotgan.example', '오세훈', NULL, '070-0000-0002', 'LOCAL', 0, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 893 DAY, '10:00:00')),
  (203, 'seller13@seed.gotgan.example', '최민재', NULL, '051-0000-0003', 'LOCAL', 0, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 1323 DAY, '10:00:00')),
  (205, 'seller15@seed.gotgan.example', '정다은', NULL, '031-0000-0005', 'LOCAL', 0, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 304 DAY, '10:00:00')),
  (207, 'seller17@seed.gotgan.example', '윤하루', NULL, '02-0000-0007', 'LOCAL', 0, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 543 DAY, '10:00:00')),
  (208, 'seller18@seed.gotgan.example', '강예린', NULL, '02-0000-0008', 'LOCAL', 0, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 723 DAY, '10:00:00')),
  (209, 'seller19@seed.gotgan.example', '서준호', NULL, '070-0000-0009', 'LOCAL', 0, 0, 'NORMAL', 'ACTIVE', NULL, NULL, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '09:00:00'), TIMESTAMP(@today - INTERVAL 213 DAY, '10:00:00'));

INSERT IGNORE INTO point_history (point_history_id, user_id, order_item_id, order_no, type, amount, shortfall_amt, balance_after, ref_key, created_at) VALUES
  (1001, 4, 1001, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901001'), 'EARN', 2900, NULL, 2900, 'seed-earn-1001', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1002, 5, 1002, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901002'), 'EARN', 2800, NULL, 2800, 'seed-earn-1002', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1003, 6, 1003, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901003'), 'EARN', 3450, NULL, 3450, 'seed-earn-1003', TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1004, 5, 1004, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901004'), 'EARN', 1950, NULL, 4750, 'seed-earn-1004', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1005, 6, 1005, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901005'), 'EARN', 1450, NULL, 4900, 'seed-earn-1005', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1006, 4, 1006, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901006'), 'EARN', 2250, NULL, 5150, 'seed-earn-1006', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1007, 4, 1007, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 4 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901007'), 'EARN', 1800, NULL, 6950, 'seed-earn-1007', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 4 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1008, 6, 1008, CONCAT(DATE_FORMAT(TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00') - INTERVAL 9 DAY, '%Y%m%d'), '-901008'), 'EARN', 2700, NULL, 7600, 'seed-earn-1008', TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00') - INTERVAL 9 DAY),
  (1009, 4, 1009, CONCAT(DATE_FORMAT(TIMESTAMP(@today - INTERVAL 5 DAY, '11:00:00'), '%Y%m%d'), '-901009'), 'EARN', 1450, NULL, 8400, 'seed-earn-1009', TIMESTAMP(@today - INTERVAL 5 DAY, '11:00:00')),
  (1010, 5, 1010, CONCAT(DATE_FORMAT(TIMESTAMP(@today - INTERVAL 3 DAY, '11:00:00'), '%Y%m%d'), '-901010'), 'EARN', 1400, NULL, 6150, 'seed-earn-1010', TIMESTAMP(@today - INTERVAL 3 DAY, '11:00:00')),
  (1011, 6, 1011, CONCAT(DATE_FORMAT(TIMESTAMP(@today - INTERVAL 2 DAY, '11:00:00'), '%Y%m%d'), '-901011'), 'EARN', 1950, NULL, 9550, 'seed-earn-1011', TIMESTAMP(@today - INTERVAL 2 DAY, '11:00:00')),
  (1012, 4, 1012, CONCAT(DATE_FORMAT(TIMESTAMP(@today - INTERVAL 1 DAY, '11:00:00'), '%Y%m%d'), '-901012'), 'EARN', 2250, NULL, 10650, 'seed-earn-1012', TIMESTAMP(@today - INTERVAL 1 DAY, '11:00:00')),
  (1013, 5, 1013, CONCAT(DATE_FORMAT(TIMESTAMP(@today - INTERVAL 1 DAY, '11:00:00'), '%Y%m%d'), '-901013'), 'EARN', 4350, NULL, 10500, 'seed-earn-1013', TIMESTAMP(@today - INTERVAL 1 DAY, '11:00:00')),
  (1014, 6, 1014, CONCAT(DATE_FORMAT(TIMESTAMP(@today - INTERVAL 1 DAY, '11:00:00'), '%Y%m%d'), '-901014'), 'EARN', 2400, NULL, 11950, 'seed-earn-1014', TIMESTAMP(@today - INTERVAL 1 DAY, '11:00:00')),
  (1040, 104, NULL, NULL, 'EARN', 1200, NULL, 1200, 'seed-base-104', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (1050, 105, NULL, NULL, 'EARN', 8800, NULL, 8800, 'seed-base-105', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (1060, 106, NULL, NULL, 'EARN', 2050, NULL, 2050, 'seed-base-106', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (1070, 107, NULL, NULL, 'EARN', 500, NULL, 500, 'seed-base-107', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (1080, 108, NULL, NULL, 'EARN', 61000, NULL, 61000, 'seed-base-108', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (1100, 110, NULL, NULL, 'EARN', 4100, NULL, 4100, 'seed-base-110', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (1120, 112, NULL, NULL, 'EARN', 9900, NULL, 9900, 'seed-base-112', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00')),
  (1130, 113, NULL, NULL, 'EARN', 3000, NULL, 3000, 'seed-base-113', TIMESTAMP(@today - INTERVAL 8 DAY, '10:00:00')),
  (1140, 114, NULL, NULL, 'EARN', 5200, NULL, 5200, 'seed-base-114', TIMESTAMP(@today - INTERVAL 30 DAY, '10:00:00'));
