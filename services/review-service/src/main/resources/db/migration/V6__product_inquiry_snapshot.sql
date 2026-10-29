-- reviewdb V6 — 상품문의 화면 표시용 스냅샷 (10/29 결정: 등록 때 product · user 를 동기 조회해 저장, 조회 때는 호출하지 않음)
-- 상품명 · 대표 사진은 GET /api/products/{id}(product), 작성자 이름은 GET /internal/users/{id}(user) — 문의 API 전이라 기존 행 없음
ALTER TABLE product_inquiry
  ADD COLUMN product_name        VARCHAR(200) NOT NULL COMMENT '등록 시점 상품명 스냅샷 — 내 문의 · 판매자 문의 목록 표시용' AFTER store_id,
  ADD COLUMN thumbnail_url       VARCHAR(500) NULL     COMMENT '등록 시점 상품 대표 사진 URL 스냅샷 — 사진 없으면 NULL' AFTER product_name,
  ADD COLUMN writer_display_name VARCHAR(50)  NOT NULL COMMENT '작성자 공개 표시 이름 (닉네임, 없으면 가운데 가린 실명)' AFTER thumbnail_url;
