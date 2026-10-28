-- reviewdb V4 — 마이페이지 리뷰 화면의 상품 대표 사진 · 산 옵션 (10/28 결정: 구매확정 이벤트 스냅샷, review 는 product 를 호출하지 않음)
-- 값은 OrderItemConfirmed.optionName · thumbnailUrl (order_item.option_name · thumbnail_url 그대로) — order payload 추가 전에 저장된 자격은 NULL
ALTER TABLE review_eligibility
  ADD COLUMN option_name   VARCHAR(200) NULL COMMENT '구매확정 시점 옵션명 스냅샷 (payload optionName, 예: 오트밀 / M) — 옵션 없는 상품은 NULL' AFTER product_name,
  ADD COLUMN thumbnail_url VARCHAR(500) NULL COMMENT '구매확정 시점 상품 대표 사진 URL 스냅샷 (payload thumbnailUrl)' AFTER option_name;
