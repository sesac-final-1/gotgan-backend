-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션)
-- 목업(gotgan_expage/src/data/*.js) 데이터를 INSERT 로 옮긴다. 담당 VoC-100 (카테고리 시드 구지훈 — 시연 데이터 일괄 작업)
--
-- 카테고리 — docs/api/product.md "시드 카테고리 ID"(10/20 확정) 그대로. 상위 6 · 하위 28, 모두 사용 중
--   상위 ID 1~6 은 settlement 수수료 시드(category_commission) · store 시드(store_application.category_id)와 같은 번호여야 한다
--   하위 ID = 상위 × 100 + 순번. 시드가 603 까지 넣으므로 관리자가 새로 만드는 카테고리는 604 부터 — 수수료 시드 번호와 겹치지 않음
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 관리자가 바꾼 이름 · 사용 여부는 유지

INSERT IGNORE INTO category (category_id, parent_id, name, name_en, sort_order, is_active) VALUES
  (1, NULL, '식품', 'Food', 4, TRUE),
  (2, NULL, '리빙·인테리어', 'Living', 3, TRUE),
  (3, NULL, '패션의류', 'Apparel', 1, TRUE),
  (4, NULL, '신발·가방', 'Shoes & Bags', 2, TRUE),
  (5, NULL, '뷰티', 'Beauty', 5, TRUE),
  (6, NULL, '디지털·가전', 'Digital', 6, TRUE);

INSERT IGNORE INTO category (category_id, parent_id, name, sort_order, is_active) VALUES
  (101, 1, '과일', 1, TRUE), (102, 1, '꿀·잼', 2, TRUE), (103, 1, '쌀·잡곡', 3, TRUE), (104, 1, '커피', 4, TRUE),
  (201, 2, '식기', 1, TRUE), (202, 2, '화병', 2, TRUE), (203, 2, '조명', 3, TRUE), (204, 2, '침구', 4, TRUE),
  (205, 2, '수납', 5, TRUE), (206, 2, '캔들', 6, TRUE),
  (301, 3, '셔츠', 1, TRUE), (302, 3, '팬츠', 2, TRUE), (303, 3, '원피스', 3, TRUE), (304, 3, '니트', 4, TRUE),
  (305, 3, '아우터', 5, TRUE), (306, 3, '티셔츠', 6, TRUE),
  (401, 4, '로퍼', 1, TRUE), (402, 4, '스니커즈', 2, TRUE), (403, 4, '토트백', 3, TRUE), (404, 4, '백팩', 4, TRUE),
  (405, 4, '크로스백', 5, TRUE),
  (501, 5, '스킨케어', 1, TRUE), (502, 5, '바디·핸드', 2, TRUE), (503, 5, '립', 3, TRUE), (504, 5, '선케어', 4, TRUE),
  (601, 6, '키보드', 1, TRUE), (602, 6, '음향', 2, TRUE), (603, 6, '데스크', 3, TRUE);
