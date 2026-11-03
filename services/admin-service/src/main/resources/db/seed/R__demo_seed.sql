-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션). 담당 jihoon-devstu
-- ⚠️ 생성 파일 — 직접 고치지 말고 gotgan-backend scripts/seed-gen/scenario.mjs 를 고친 뒤 `node scripts/seed-gen/gen.mjs` (구지훈)
-- 서비스끼리 같은 번호 · 금액 · 날짜를 가리킨다(store_id · user_id · order_item_id · 주문번호). 날짜는 실행일 기준 상대값(KST)
-- 신고 · 조치 = 블라인드 리뷰(review 시드 1004) · 정지 회원(user 시드 111)의 근거. 조치 1번이 그 대상의 신고를 모두 닫음(RESOLVED)
--   조치 시각 = review blinded_at · user suspended_at, 사유 = 대상 서비스에 넣은 사유와 같음. 처리한 관리자 = user 1. 처리 대기 신고는 없음(시연에서 새로 신고)
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 바뀐 값은 유지

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);            -- KST 오늘 (DB 시간대와 무관)
SET @w1    := @today - INTERVAL (WEEKDAY(@today) + 7) DAY;        -- 지난주 월요일

INSERT IGNORE INTO moderation_action (moderation_action_id, target_type, target_id, action, reason, suspend_days, admin_id, report_count, created_at) VALUES
  (1, 'REVIEW', 1004, 'BLIND', '상품과 무관한 외부 판매처 홍보 · 광고 (신고 3건 확인)', NULL, 1, 3, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY),
  (2, 'USER', 111, 'SUSPEND', '판매자 대상 욕설 · 허위 신고 반복 (신고 2건 확인)', 14, 1, 2, TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00'));

INSERT IGNORE INTO report (report_id, target_type, target_id, reporter_id, reporter_role, reason_code, detail, target_summary, status, moderation_action_id, created_at, updated_at) VALUES
  (1, 'REVIEW', 1004, 4, 'BUYER', 'SPAM', NULL, '꿀 맛은 잘 모르겠고 같은 꿀 다른 마켓에서 훨씬 싸게 팝니다. 검색창에 할인코드 입력하면 30% 더 빠져요. 댓글로 링크 문의 주세요.', 'RESOLVED', 1, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY - INTERVAL 1 DAY + INTERVAL 0 HOUR, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY),
  (2, 'REVIEW', 1004, 6, 'BUYER', 'SPAM', NULL, '꿀 맛은 잘 모르겠고 같은 꿀 다른 마켓에서 훨씬 싸게 팝니다. 검색창에 할인코드 입력하면 30% 더 빠져요. 댓글로 링크 문의 주세요.', 'RESOLVED', 1, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY - INTERVAL 1 DAY + INTERVAL 3 HOUR, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY),
  (3, 'REVIEW', 1004, 2, 'SELLER', 'SELLER_REQUEST', NULL, '꿀 맛은 잘 모르겠고 같은 꿀 다른 마켓에서 훨씬 싸게 팝니다. 검색창에 할인코드 입력하면 30% 더 빠져요. 댓글로 링크 문의 주세요.', 'RESOLVED', 1, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY - INTERVAL 1 DAY + INTERVAL 6 HOUR, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY),
  (4, 'USER', 111, 2, 'SELLER', 'ABUSE', NULL, '오준서', 'RESOLVED', 2, TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00') - INTERVAL 1 DAY + INTERVAL 0 HOUR, TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00')),
  (5, 'USER', 111, 3, 'SELLER', 'ABUSE', NULL, '오준서', 'RESOLVED', 2, TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00') - INTERVAL 1 DAY + INTERVAL 3 HOUR, TIMESTAMP(@today - INTERVAL 4 DAY, '10:00:00'));
