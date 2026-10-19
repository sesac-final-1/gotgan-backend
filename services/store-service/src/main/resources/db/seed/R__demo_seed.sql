-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션)
-- 목업(gotgan_expage/src/data/*.js) 데이터를 INSERT 로 옮긴다. 담당 VoC-100
--
-- 시연 판매자 스토어 2개 — Keycloak 시드(gotgan-config scripts/keycloak-seed.sh) · userdb 시드와 **user_id · store_id 가 같아야 한다**
--   seller1@gotgan.com  user_id 2 → store_id 1 (Keycloak 속성 store_id=1 → 게이트웨이 X-Store-Id)
--   seller2@gotgan.com  user_id 3 → store_id 2
-- store 는 승인된 입점 신청서(store_application, FK)에서 생기므로 신청서 · 이력도 함께 넣는다 (승인 흐름을 거친 것과 같은 상태)
-- category_id 1 · 2 는 productdb category 논리 참조 — product 시드에서 같은 번호의 카테고리를 만들어야 한다
-- 사업자 · 계좌 정보는 시연용 가짜 값. 서류(application_document)는 S3 객체가 없어 넣지 않는다
-- 이벤트(StoreApproved)는 발행되지 않는다 — settlement 의 스토어 스냅샷 등 이벤트로 만들어지는 데이터는 각 서비스 시드에서 맞춘다
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE — Flyway 로그에 Duplicate 경고만 남음) — 시연 중 바뀐 스토어 정보 · 상태는 유지

INSERT IGNORE INTO store_application
  (store_application_id, user_id, category_id, biz_type, company_name, owner_name, biz_reg_no, ecommerce_reg_no,
   manager_phone, manager_email, business_address, store_name, slug, intro, representative_products,
   bank_name, account_no, account_holder, status, marketing_agreed, terms_agreed_at, status_changed_at)
VALUES
  (1, 2, 1, 'INDIVIDUAL', '곳간상회', '판매자1', '123-45-67890', '2026-서울강남-0001',
   '010-0000-0002', 'seller1@gotgan.com', '서울 강남구 테헤란로 1', '곳간상회', 'gotgan-sanghoe', '<p>매일 고르는 제철 먹거리</p>',
   JSON_ARRAY(JSON_OBJECT('name', '제철 사과 3kg', 'subCategory', '과일', 'price', 25000),
              JSON_OBJECT('name', '햇배 5kg', 'subCategory', '과일', 'price', 32000),
              JSON_OBJECT('name', '감귤 5kg', 'subCategory', '과일', 'price', 18000)),
   '곳간은행', '100-0000-000001', '판매자1', 'APPROVED', FALSE, NOW(6), NOW(6)),
  (2, 3, 2, 'CORPORATE', '(주)곳간리빙', '판매자2', '234-56-78901', '2026-서울마포-0002',
   '010-0000-0003', 'seller2@gotgan.com', '서울 마포구 월드컵북로 1', '곳간리빙', 'gotgan-living', '<p>집을 채우는 생활용품</p>',
   JSON_ARRAY(JSON_OBJECT('name', '세라믹 머그컵', 'subCategory', '주방', 'price', 12000),
              JSON_OBJECT('name', '린넨 앞치마', 'subCategory', '주방', 'price', 19000),
              JSON_OBJECT('name', '원목 도마', 'subCategory', '주방', 'price', 27000)),
   '곳간은행', '100-0000-000002', '(주)곳간리빙', 'APPROVED', TRUE, NOW(6), NOW(6));

-- 신청 이력 — 제출(PENDING) → 승인(APPROVED)
INSERT IGNORE INTO application_history (application_history_id, store_application_id, from_status, to_status, actor_type, reason)
VALUES
  (1, 1, NULL,      'PENDING',  'APPLICANT', NULL),
  (2, 1, 'PENDING', 'APPROVED', 'ADMIN',     NULL),
  (3, 2, NULL,      'PENDING',  'APPLICANT', NULL),
  (4, 2, 'PENDING', 'APPROVED', 'ADMIN',     NULL);

-- 스토어 — 승인 시 신청서에서 복사되는 값(스토어명 · 스토어 주소 · 소개 · 담당자 휴대폰)과 같게. 표시 색 · 등급은 기본값(V2)
INSERT IGNORE INTO store
  (store_id, store_application_id, user_id, store_name, slug, intro, cs_phone, shipping_fee, free_shipping_threshold,
   courier, dispatch_note, status)
VALUES
  (1, 1, 2, '곳간상회', 'gotgan-sanghoe', '<p>매일 고르는 제철 먹거리</p>', '010-0000-0002', 3000, 30000,
   'CJ대한통운', '평일 14시 이전 결제 시 당일 출고', 'ACTIVE'),
  (2, 2, 3, '곳간리빙', 'gotgan-living',  '<p>집을 채우는 생활용품</p>',   '010-0000-0003', 3000, NULL,
   '한진택배',   '결제 후 1~2영업일 출고', 'ACTIVE');

-- 스토어 상태 이력 — 승인으로 생성 (from_status NULL)
INSERT IGNORE INTO store_status_history (store_status_history_id, store_id, from_status, to_status, actor_type, reason)
VALUES
  (1, 1, NULL, 'ACTIVE', 'ADMIN', '입점 승인'),
  (2, 2, NULL, 'ACTIVE', 'ADMIN', '입점 승인');
