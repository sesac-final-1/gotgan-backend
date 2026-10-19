-- storedb V2 — store-service (담당 박지연)
-- 근거: API 명세 시트 · 프론트 목업 대조(10/19) — 스토어 주소(slug) · 표시 색 · 등급 · 택배사 · 출고 안내 · 월 예상 매출 · 운영 채널이 화면에 있고 V1 에 없음
-- V1 은 머지돼 수정 금지 — 빠진 컬럼을 V2 로 추가. 컬럼 위치는 컨벤션 3-9 순서에 맞춰 AFTER 로 지정.
-- 관심고객 수(followers)는 저장하지 않음 — 팔로우 기능 범위 밖 (API 에서 제거)

-- 입점 신청서 — 신청 폼 "스토어 주소" · "월 예상 매출" · "운영 중인 채널"
ALTER TABLE store_application
  ADD COLUMN slug                   VARCHAR(50)  NOT NULL COMMENT '신청한 스토어 주소 gotgan.kr/store/{slug} — 승인 시 store.slug 로 복사. 형식(영소문자 · 숫자 · -)과 중복은 서비스 검증' AFTER store_name,
  ADD COLUMN expected_monthly_sales VARCHAR(20)           COMMENT '월 예상 매출 UNDER_5M / FROM_5M_TO_20M / FROM_20M_TO_100M / OVER_100M — 심사 화면 표시용' AFTER representative_products,
  ADD COLUMN sales_channel          VARCHAR(255)          COMMENT '운영 중인 채널 (자사몰 · 인스타그램 등 URL) — 선택 입력, 심사 참고용' AFTER expected_monthly_sales,
  ADD INDEX idx_store_application_biz_reg_no (biz_reg_no);  -- 중복 입점 확인 (STR-10) — 같은 사업자번호의 이전 신청 · 스토어를 심사 상세에 표시

-- 스토어 — 스토어 주소 · 표시 색 · 기본 택배사 · 출고 안내 · 판매자 등급
ALTER TABLE store
  ADD COLUMN slug          VARCHAR(50)  NOT NULL COMMENT '스토어 주소 gotgan.kr/store/{slug} — 승인 시 신청서에서 복사, 판매자는 수정 불가' AFTER store_name,
  ADD COLUMN color         VARCHAR(7)   NOT NULL DEFAULT '#5b6b4a' COMMENT '스토어 표시 색 (#RRGGBB) — 이미지 없을 때 아바타 배경, 판매자 설정에서 선택' AFTER image_key,
  ADD COLUMN courier       VARCHAR(30)           COMMENT '기본 택배사 이름 예: CJ대한통운 — 상품 상세 배송 안내 · 송장 입력 기본값 (order_item.courier 와 같은 형식). 승인 직후 NULL' AFTER free_shipping_threshold,
  ADD COLUMN dispatch_note VARCHAR(100)          COMMENT '출고 안내 문구 예: 평일 14시 이전 결제 시 당일 출고' AFTER courier,
  ADD COLUMN grade         VARCHAR(20)  NOT NULL DEFAULT 'SPROUT' COMMENT '판매자 등급 SPROUT(새싹) / POWER(파워) / BIG_POWER(빅파워) — 승인 시 SPROUT, 승급 기준은 미정' AFTER dispatch_note,
  ADD CONSTRAINT uk_store_slug UNIQUE (slug),              -- 스토어 주소 하나 = 스토어 하나 (동시 신청은 승인 때 여기서 차단)
  ADD CONSTRAINT uk_store_store_name UNIQUE (store_name);  -- 스토어명 중복 금지 — 공백 · 대소문자 차이는 서비스 검증에서 정규화
