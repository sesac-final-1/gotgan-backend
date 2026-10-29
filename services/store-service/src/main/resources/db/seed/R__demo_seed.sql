-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션). 담당 VoC-100
-- ⚠️ 생성 파일 — 직접 고치지 말고 gotgan-backend scripts/seed-gen/scenario.mjs 를 고친 뒤 `node scripts/seed-gen/gen.mjs` (구지훈)
-- 서비스끼리 같은 번호 · 금액 · 날짜를 가리킨다(store_id · user_id · order_item_id · 주문번호). 날짜는 실행일 기준 상대값(KST)
-- 목업 스토어 9개(프론트 mocks/fixtures/catalog.js) — 모두 승인된 입점 신청서에서 생긴 상태로 신청서 · 이력도 함께
--   store_id = 10 + 목업 번호. 단 시연 판매자 로그인 스토어 1 · 2 는 Keycloak 시드 · settlement 시드와 번호가 같아야 해서
--   seller1 user 2 → store 1 (산골농부 박씨네, 식품) · seller2 user 3 → store 2 (토담공방, 리빙). 나머지 대표자는 user 200 + 목업 번호(조회용)
-- category_id = productdb 카테고리 시드(1~6). 신청서 id = 스토어 id, 이력 id = 스토어 id × 10 + 순번. 볕드는집(15)은 정지
-- 심사 시연용 신청 101(심사대기, buyer3 user 6) · 102(보완요청, buyer2 user 5 — 4일 전 신청 · 2일 전 보완요청). 스토어 없음
-- 사업자 · 계좌는 가짜 값. 서류(application_document)는 S3 객체가 없어 넣지 않는다. 이벤트는 발행되지 않는다
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 바뀐 값은 유지

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);            -- KST 오늘 (DB 시간대와 무관)
SET @w1    := @today - INTERVAL (WEEKDAY(@today) + 7) DAY;        -- 지난주 월요일

INSERT IGNORE INTO store_application (store_application_id, user_id, category_id, biz_type, company_name, owner_name, biz_reg_no, ecommerce_reg_no, manager_phone, manager_email, business_address, store_name, slug, intro, representative_products, expected_monthly_sales, sales_channel, bank_name, account_no, account_holder, status, status_reason, marketing_agreed, terms_agreed_at, status_changed_at, created_at) VALUES
  (1, 2, 1, 'INDIVIDUAL', '산골농부 박씨네', '박영철', '000-00-00006', '2026-시연-0001', '064-0000-0006', 'wlsgksvheh+seller1@gmail.com', '서울 성동구 성수이로 1', '산골농부 박씨네', 'park-farm', '<p>제주 서귀포에서 3대째 귤 농사를 짓습니다. 따는 날 바로 보내드려요.</p>', JSON_ARRAY(JSON_OBJECT('name', '제철 과일 선물 세트', 'subCategory', '과일', 'price', 32000), JSON_OBJECT('name', '수제 과일청 2병', 'subCategory', '꿀·잼', 'price', 18000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000001', '박영철', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 983 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 980 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 983 DAY, '10:00:00')),
  (2, 3, 2, 'INDIVIDUAL', '토담공방', '이수아', '000-00-00004', '2026-시연-0002', '063-0000-0004', 'wlsgksvheh+seller2@gmail.com', '서울 성동구 성수이로 2', '토담공방', 'todam', '<p>전주 한옥마을 옆 작은 도자기 공방. 손으로 빚어 하나하나 모양이 다릅니다.</p>', JSON_ARRAY(JSON_OBJECT('name', '손빚음 머그', 'subCategory', '식기', 'price', 28000), JSON_OBJECT('name', '리넨 패브릭 조명', 'subCategory', '조명', 'price', 54000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000002', '이수아', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 458 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 455 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 458 DAY, '10:00:00')),
  (11, 201, 3, 'INDIVIDUAL', '오롯이 리넨', '한지우', '000-00-00001', '2026-시연-0011', '02-0000-0001', 'hello@orotlinen.example.com', '서울 성동구 성수이로 11', '오롯이 리넨', 'orot-linen', '<p>여름이 아니어도 입고 싶은 리넨. 성수동 작업실에서 직접 패턴을 뜨고 봉제합니다.</p>', JSON_ARRAY(JSON_OBJECT('name', '리넨 셔츠', 'subCategory', '셔츠', 'price', 69000), JSON_OBJECT('name', '와이드 팬츠', 'subCategory', '팬츠', 'price', 59000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000011', '한지우', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 615 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 612 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 615 DAY, '10:00:00')),
  (12, 202, 3, 'INDIVIDUAL', '무드앤로우', '오세훈', '000-00-00002', '2026-시연-0012', '070-0000-0002', 'cs@moodrow.example.com', '서울 성동구 성수이로 12', '무드앤로우', 'mood-and-row', '<p>매일 입는 옷을 더 오래. 두꺼운 원단, 단정한 핏의 베이직 웨어.</p>', JSON_ARRAY(JSON_OBJECT('name', '리넨 셔츠', 'subCategory', '셔츠', 'price', 69000), JSON_OBJECT('name', '와이드 팬츠', 'subCategory', '팬츠', 'price', 59000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000012', '오세훈', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 893 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 890 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 893 DAY, '10:00:00')),
  (13, 203, 4, 'INDIVIDUAL', '걸음상회', '최민재', '000-00-00003', '2026-시연-0013', '051-0000-0003', 'georum@georum.example.com', '서울 성동구 성수이로 13', '걸음상회', 'georum', '<p>부산 신발 공장 3대째. 오래 걸어도 편한 가죽 신발과 가방.</p>', JSON_ARRAY(JSON_OBJECT('name', '가죽 로퍼', 'subCategory', '로퍼', 'price', 129000), JSON_OBJECT('name', '데일리 토트백', 'subCategory', '토트백', 'price', 89000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000013', '최민재', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 1323 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 1320 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 1323 DAY, '10:00:00')),
  (15, 205, 2, 'INDIVIDUAL', '볕드는집', '정다은', '000-00-00005', '2026-시연-0015', '031-0000-0005', 'house@byeot.example.com', '서울 성동구 성수이로 15', '볕드는집', 'byeot-house', '<p>햇볕 잘 드는 방을 위한 조명과 패브릭.</p>', JSON_ARRAY(JSON_OBJECT('name', '손빚음 머그', 'subCategory', '식기', 'price', 28000), JSON_OBJECT('name', '리넨 패브릭 조명', 'subCategory', '조명', 'price', 54000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000015', '정다은', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 304 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 301 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 304 DAY, '10:00:00')),
  (17, 207, 1, 'INDIVIDUAL', '로스터리 하루', '윤하루', '000-00-00007', '2026-시연-0017', '02-0000-0007', 'roast@haru.example.com', '서울 성동구 성수이로 17', '로스터리 하루', 'haru-roastery', '<p>월·목 로스팅, 화·금 발송. 볶은 지 3일 이내 원두만 보냅니다.</p>', JSON_ARRAY(JSON_OBJECT('name', '제철 과일 선물 세트', 'subCategory', '과일', 'price', 32000), JSON_OBJECT('name', '수제 과일청 2병', 'subCategory', '꿀·잼', 'price', 18000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000017', '윤하루', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 543 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 540 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 543 DAY, '10:00:00')),
  (18, 208, 5, 'INDIVIDUAL', '피부정원', '강예린', '000-00-00008', '2026-시연-0018', '02-0000-0008', 'garden@skingarden.example.com', '서울 성동구 성수이로 18', '피부정원', 'skin-garden', '<p>성분은 줄이고 효과는 그대로. 민감 피부를 위한 저자극 스킨케어.</p>', JSON_ARRAY(JSON_OBJECT('name', '진정 토너 200ml', 'subCategory', '스킨케어', 'price', 24000), JSON_OBJECT('name', '수분크림 50ml', 'subCategory', '스킨케어', 'price', 32000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000018', '강예린', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 723 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 720 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 723 DAY, '10:00:00')),
  (19, 209, 6, 'INDIVIDUAL', '데스크테리어랩', '서준호', '000-00-00009', '2026-시연-0019', '070-0000-0009', 'lab@deskterior.example.com', '서울 성동구 성수이로 19', '데스크테리어랩', 'deskterior-lab', '<p>하루 8시간 앉는 책상을 위한 도구들.</p>', JSON_ARRAY(JSON_OBJECT('name', '무접점 키보드', 'subCategory', '키보드', 'price', 219000), JSON_OBJECT('name', '원목 모니터 받침대', 'subCategory', '데스크', 'price', 89000)), 'FROM_5M_TO_20M', NULL, '곳간은행', '100-0000-000019', '서준호', 'APPROVED', NULL, FALSE, TIMESTAMP(@today - INTERVAL 213 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 210 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 213 DAY, '10:00:00')),
  (101, 6, 5, 'INDIVIDUAL', '지민이네 수제비누', '박지민', '000-00-00101', '2026-시연-101', '010-0000-0012', 'wlsgksvheh+buyer3@gmail.com', '서울 성동구 성수이로 101', '지민이네 수제비누', 'jimin-soap', '<p>천연 오일로 저온 숙성한 수제 비누를 만듭니다.</p>', JSON_ARRAY(JSON_OBJECT('name', '진정 토너 200ml', 'subCategory', '스킨케어', 'price', 24000), JSON_OBJECT('name', '수분크림 50ml', 'subCategory', '스킨케어', 'price', 32000)), 'UNDER_5M', NULL, '곳간은행', '100-0000-000101', '박지민', 'PENDING', NULL, FALSE, TIMESTAMP(@today - INTERVAL 1 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 1 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 1 DAY, '10:00:00')),
  (102, 5, 2, 'INDIVIDUAL', '도현 목공소', '이도현', '000-00-00102', '2026-시연-102', '010-0000-0011', 'wlsgksvheh+buyer2@gmail.com', '서울 성동구 성수이로 102', '도현 목공소', 'dohyun-wood', '<p>원목 도마와 트레이를 하나씩 깎아 만듭니다.</p>', JSON_ARRAY(JSON_OBJECT('name', '손빚음 머그', 'subCategory', '식기', 'price', 28000), JSON_OBJECT('name', '리넨 패브릭 조명', 'subCategory', '조명', 'price', 54000)), 'UNDER_5M', NULL, '곳간은행', '100-0000-000102', '이도현', 'SUPPLEMENT', '통신판매업 신고증 사본이 흐려 확인이 어렵습니다. 다시 올려 주세요.', FALSE, TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 2 DAY, '10:00:00'), TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00'));

INSERT IGNORE INTO application_history (application_history_id, store_application_id, from_status, to_status, actor_type, reason, created_at) VALUES
  (11, 1, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 983 DAY, '10:00:00')),
  (12, 1, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 980 DAY, '10:00:00')),
  (21, 2, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 458 DAY, '10:00:00')),
  (22, 2, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 455 DAY, '10:00:00')),
  (111, 11, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 615 DAY, '10:00:00')),
  (112, 11, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 612 DAY, '10:00:00')),
  (121, 12, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 893 DAY, '10:00:00')),
  (122, 12, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 890 DAY, '10:00:00')),
  (131, 13, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 1323 DAY, '10:00:00')),
  (132, 13, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 1320 DAY, '10:00:00')),
  (151, 15, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 304 DAY, '10:00:00')),
  (152, 15, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 301 DAY, '10:00:00')),
  (171, 17, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 543 DAY, '10:00:00')),
  (172, 17, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 540 DAY, '10:00:00')),
  (181, 18, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 723 DAY, '10:00:00')),
  (182, 18, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 720 DAY, '10:00:00')),
  (191, 19, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 213 DAY, '10:00:00')),
  (192, 19, 'PENDING', 'APPROVED', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 210 DAY, '10:00:00')),
  (1011, 101, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '10:00:00')),
  (1021, 102, NULL, 'PENDING', 'APPLICANT', NULL, TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00')),
  (1022, 102, 'PENDING', 'SUPPLEMENT', 'ADMIN', '통신판매업 신고증 사본이 흐려 확인이 어렵습니다. 다시 올려 주세요.', TIMESTAMP(@today - INTERVAL 2 DAY, '10:00:00'));

INSERT IGNORE INTO store (store_id, store_application_id, user_id, store_name, slug, intro, color, cs_phone, shipping_fee, free_shipping_threshold, courier, dispatch_note, grade, status, status_reason, created_at) VALUES
  (1, 1, 2, '산골농부 박씨네', 'park-farm', '<p>제주 서귀포에서 3대째 귤 농사를 짓습니다. 따는 날 바로 보내드려요.</p>', '#d0701f', '064-0000-0006', 3000, 30000, '우체국택배', '수확일 기준 당일 발송', 'BIG_POWER', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 980 DAY, '10:00:00')),
  (2, 2, 3, '토담공방', 'todam', '<p>전주 한옥마을 옆 작은 도자기 공방. 손으로 빚어 하나하나 모양이 다릅니다.</p>', '#8c6d4f', '063-0000-0004', 3500, 50000, 'CJ대한통운', '주문 제작 3~5영업일', 'POWER', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 455 DAY, '10:00:00')),
  (11, 11, 201, '오롯이 리넨', 'orot-linen', '<p>여름이 아니어도 입고 싶은 리넨. 성수동 작업실에서 직접 패턴을 뜨고 봉제합니다.</p>', '#5b6b4a', '02-0000-0001', 3000, 50000, 'CJ대한통운', '평일 14시 이전 결제 시 당일 출고', 'POWER', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 612 DAY, '10:00:00')),
  (12, 12, 202, '무드앤로우', 'mood-and-row', '<p>매일 입는 옷을 더 오래. 두꺼운 원단, 단정한 핏의 베이직 웨어.</p>', '#2b2b2b', '070-0000-0002', 0, NULL, '롯데택배', '결제 후 1~2영업일 출고', 'BIG_POWER', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 890 DAY, '10:00:00')),
  (13, 13, 203, '걸음상회', 'georum', '<p>부산 신발 공장 3대째. 오래 걸어도 편한 가죽 신발과 가방.</p>', '#7a4b2a', '051-0000-0003', 3000, 70000, '우체국택배', '평일 13시 이전 결제 시 당일 출고', 'BIG_POWER', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 1320 DAY, '10:00:00')),
  (15, 15, 205, '볕드는집', 'byeot-house', '<p>햇볕 잘 드는 방을 위한 조명과 패브릭.</p>', '#c08a2e', '031-0000-0005', 3000, 40000, '한진택배', '평일 13시 이전 결제 시 당일 출고', 'SPROUT', 'SUSPENDED', '반품 미처리 3건 — 처리 완료 후 해제', TIMESTAMP(@today - INTERVAL 301 DAY, '10:00:00')),
  (17, 17, 207, '로스터리 하루', 'haru-roastery', '<p>월·목 로스팅, 화·금 발송. 볶은 지 3일 이내 원두만 보냅니다.</p>', '#3d2b22', '02-0000-0007', 3000, 30000, 'CJ대한통운', '화 · 금 발송', 'POWER', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 540 DAY, '10:00:00')),
  (18, 18, 208, '피부정원', 'skin-garden', '<p>성분은 줄이고 효과는 그대로. 민감 피부를 위한 저자극 스킨케어.</p>', '#6f8f7a', '02-0000-0008', 3000, 30000, '롯데택배', '평일 15시 이전 결제 시 당일 출고', 'POWER', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 720 DAY, '10:00:00')),
  (19, 19, 209, '데스크테리어랩', 'deskterior-lab', '<p>하루 8시간 앉는 책상을 위한 도구들.</p>', '#34495e', '070-0000-0009', 3000, 50000, 'CJ대한통운', '평일 14시 이전 결제 시 당일 출고', 'SPROUT', 'ACTIVE', NULL, TIMESTAMP(@today - INTERVAL 210 DAY, '10:00:00'));

INSERT IGNORE INTO store_status_history (store_status_history_id, store_id, from_status, to_status, actor_type, reason, created_at) VALUES
  (11, 1, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 980 DAY, '10:00:00')),
  (21, 2, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 455 DAY, '10:00:00')),
  (111, 11, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 612 DAY, '10:00:00')),
  (121, 12, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 890 DAY, '10:00:00')),
  (131, 13, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 1320 DAY, '10:00:00')),
  (151, 15, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 301 DAY, '10:00:00')),
  (152, 15, 'ACTIVE', 'SUSPENDED', 'ADMIN', '반품 미처리 3건 — 처리 완료 후 해제', TIMESTAMP(@today - INTERVAL 31 DAY, '10:00:00')),
  (171, 17, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 540 DAY, '10:00:00')),
  (181, 18, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 720 DAY, '10:00:00')),
  (191, 19, NULL, 'ACTIVE', 'ADMIN', NULL, TIMESTAMP(@today - INTERVAL 210 DAY, '10:00:00'));
