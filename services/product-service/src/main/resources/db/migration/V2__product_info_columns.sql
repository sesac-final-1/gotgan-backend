-- productdb V2 — product-service (담당 박지연)
-- 근거: API 명세 시트 대조(10/19) — 카테고리 영문명 · 상품정보 제공고시(소재 · 제조국)가 화면 · API 에 있고 V1 에 없음
-- V1 은 머지돼 수정 금지 — 빠진 컬럼을 V2 로 추가. 컬럼 위치는 컨벤션 3-9 순서에 맞춰 AFTER 로 지정.
-- 태그(tags)는 저장하지 않음 — 판매자 입력 칸이 없고 PRD-06 검색 조건에도 없음 (API 에서 제거)

-- 홈 카테고리 타일 · 목록 제목 옆 영문명. 관리자가 입력하는 선택 값
ALTER TABLE category
  ADD COLUMN name_en VARCHAR(50) COMMENT '카테고리 영문명 예: Apparel — 선택 입력, 없으면 화면에서 표시하지 않음' AFTER name;

-- 상품정보 제공고시 — 모든 카테고리에서 쓰는 항목 (식품은 소재 = 원재료, 제조국 = 원산지로 표시)
ALTER TABLE product
  ADD COLUMN material VARCHAR(100) NOT NULL COMMENT '소재 · 원재료 (상품정보 제공고시) 예: 리넨 100%' AFTER description,
  ADD COLUMN origin   VARCHAR(50)  NOT NULL COMMENT '제조국 · 원산지 (상품정보 제공고시) 예: 대한민국' AFTER material;
