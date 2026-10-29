-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션). 담당 perish95
-- ⚠️ 생성 파일 — 직접 고치지 말고 gotgan-backend scripts/seed-gen/scenario.mjs 를 고친 뒤 `node scripts/seed-gen/gen.mjs` (구지훈)
-- 서비스끼리 같은 번호 · 금액 · 날짜를 가리킨다(store_id · user_id · order_item_id · 주문번호). 날짜는 실행일 기준 상대값(KST)
-- 작성 자격 = order 시드의 구매확정 상품주문 전부(review_eligibility_id = order_item_id), 값은 OrderItemConfirmed payload 와 같게
--   buyer_display_name = order 시드 orderer_display_name(마스킹 실명), option_name = order_item.option_name, thumbnail_url = order_item.thumbnail_url(시드는 NULL)
-- 리뷰 id = 상품주문 id. buyer1(user 4)은 1001 만 작성 — 1006 · 1007 은 '작성 가능한 리뷰'로 남김. 블라인드 1건(신고 처리 시연) · 판매자 답글 일부
-- 상품 문의 = 스토어 1 · 2 상품 위주(공개 · 비밀글, 답변완료 · 미답변 섞음). 작성자 표시 이름 = 닉네임, thumbnail_url 은 NULL(조회 URL 이 환경마다 달라 비움)
-- 리뷰 사진(review_image)은 S3 reviews/ 객체가 없어 넣지 않는다. 평균 · 리뷰 수는 조회 때 집계(요약 테이블 없음). 이벤트 멱등(processed_event) 행 없음
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 바뀐 값은 유지

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);            -- KST 오늘 (DB 시간대와 무관)
SET @w1    := @today - INTERVAL (WEEKDAY(@today) + 7) DAY;        -- 지난주 월요일

INSERT IGNORE INTO review_eligibility (review_eligibility_id, order_item_id, user_id, product_id, store_id, buyer_display_name, product_name, option_name, thumbnail_url, confirmed_at, expires_at, created_at) VALUES
  (1001, 1001, 4, 101, 1, '김*연', '서귀포 노지 햇감귤 5kg', '로얄과 (S~M)', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00')),
  (1002, 1002, 5, 201, 2, '이*현', '분청 손잡이 머그 350ml', '백토', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00')),
  (1003, 1003, 6, 1101, 11, '박*민', '프렌치 리넨 오버핏 셔츠', '오트밀 / S', NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00')),
  (1004, 1004, 5, 102, 1, '이*현', '지리산 야생화 꿀 1.2kg', NULL, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00')),
  (1005, 1005, 6, 101, 1, '박*민', '서귀포 노지 햇감귤 5kg', '로얄과 (S~M)', NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00')),
  (1006, 1006, 4, 202, 2, '김*연', '백자 림 접시 3종 세트', 'S+M+L', NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 3 DAY, '10:00:00')),
  (1007, 1007, 4, 1701, 17, '김*연', '에티오피아 예가체프 G1 원두 200g', '홀빈', NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 4 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 4 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 4 DAY, '10:00:00')),
  (1008, 1008, 6, 1501, 15, '박*민', '오크 베이스 패브릭 테이블 램프', '전구색 2700K', NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00'), TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00') + INTERVAL 30 DAY, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00'));

INSERT IGNORE INTO review (review_id, review_eligibility_id, order_item_id, user_id, product_id, store_id, writer_display_name, rating, title, content, fit, reply_content, is_blinded, blind_reason, replied_at, blinded_at, created_at) VALUES
  (1001, 1001, 1001, 4, 101, 1, '김*연', 5, '선물용으로도 손색없어요', '껍질이 얇고 새콤달콤해서 앉은자리에서 다섯 개는 먹었어요. 박스 안에 무른 것 하나 없이 크기도 고르게 왔습니다. 부모님 댁에도 한 박스 보내드렸어요.', NULL, '맛있게 드셔 주셔서 감사합니다! 부모님께도 좋은 귤로 골라 보내드릴게요.', FALSE, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00') + INTERVAL 1 DAY + INTERVAL 5 HOUR + INTERVAL 1 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 1 DAY, '10:00:00') + INTERVAL 1 DAY + INTERVAL 5 HOUR),
  (1002, 1002, 1002, 5, 201, 2, '이*현', 4, '손에 착 감기는 머그', '유약 색이 사진보다 조금 진하지만 오히려 더 마음에 들어요. 손잡이가 두툼해서 잡기 편하고, 350ml 라 라테 한 잔 담기 딱 좋습니다.', NULL, '손으로 빚어 하나하나 색이 조금씩 달라요. 오래 아껴 써 주세요 :)', FALSE, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00') + INTERVAL 2 DAY + INTERVAL 5 HOUR + INTERVAL 1 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 2 DAY, '10:00:00') + INTERVAL 2 DAY + INTERVAL 5 HOUR),
  (1003, 1003, 1003, 6, 1101, 11, '박*민', 3, '생각보다 크게 나와요', '리넨 감촉은 좋은데 오버핏이라 평소 M 입는 제가 S 를 샀는데도 품이 넉넉합니다. 정사이즈보다 크게 나오니 참고하세요. 세탁 후 구김은 각오하셔야 해요.', 'LARGE', NULL, FALSE, NULL, NULL, NULL, TIMESTAMP(@w1 - INTERVAL 7 DAY + INTERVAL 3 DAY, '10:00:00') + INTERVAL 1 DAY + INTERVAL 5 HOUR),
  (1004, 1004, 1004, 5, 102, 1, '이*현', 1, '여기보다 싼 곳 있어요', '꿀 맛은 잘 모르겠고 같은 꿀 다른 마켓에서 훨씬 싸게 팝니다. 검색창에 할인코드 입력하면 30% 더 빠져요. 댓글로 링크 문의 주세요.', NULL, NULL, TRUE, '상품과 무관한 외부 판매처 홍보 · 광고 (신고 3건 확인)', NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 3 DAY, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 0 DAY, '10:00:00') + INTERVAL 1 DAY + INTERVAL 5 HOUR),
  (1005, 1005, 1005, 6, 101, 1, '박*민', 2, '몇 개는 물러서 왔어요', '맛은 좋은데 택배 오는 동안 눌렸는지 아래쪽 귤 서너 개가 물러 있었어요. 완충재를 조금 더 넣어 주시면 좋겠습니다.', NULL, '불편을 드려 죄송합니다. 다음 발송부터 완충재를 보강하고, 무른 귤은 사진 보내 주시면 바로 다시 보내드릴게요.', FALSE, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00') + INTERVAL 1 DAY + INTERVAL 5 HOUR + INTERVAL 1 DAY, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 2 DAY, '10:00:00') + INTERVAL 1 DAY + INTERVAL 5 HOUR),
  (1008, 1008, 1008, 6, 1501, 15, '박*민', 4, NULL, '전구색으로 켜 두면 방 분위기가 확 따뜻해져요. 오크 베이스가 묵직해서 흔들림이 없고 갓 패브릭도 고급스럽습니다. 스위치가 선 중간에 있는 게 조금 아쉬워요.', NULL, NULL, FALSE, NULL, NULL, NULL, TIMESTAMP(@w1 - INTERVAL 0 DAY + INTERVAL 1 DAY, '10:00:00') + INTERVAL 2 DAY + INTERVAL 5 HOUR);

INSERT IGNORE INTO product_inquiry (product_inquiry_id, user_id, product_id, store_id, product_name, thumbnail_url, writer_display_name, content, answer_content, is_secret, answered_at, created_at) VALUES
  (1, 4, 101, 1, '서귀포 노지 햇감귤 5kg', NULL, '서연이네', '로얄과랑 대과는 당도 차이가 있나요? 아이들 간식용으로 사려고 해요.', '당도는 거의 같고 크기만 달라요. 아이들 간식용이면 한 입에 쏙 들어가는 로얄과를 추천드립니다.', FALSE, TIMESTAMP(@today - INTERVAL 5 DAY, '16:00:00'), TIMESTAMP(@today - INTERVAL 6 DAY, '13:00:00')),
  (2, 5, 101, 1, '서귀포 노지 햇감귤 5kg', NULL, '도현', '오늘 주문하면 이번 주 토요일 전에 받을 수 있을까요?', NULL, FALSE, NULL, TIMESTAMP(@today - INTERVAL 1 DAY, '13:00:00')),
  (3, 6, 102, 1, '지리산 야생화 꿀 1.2kg', NULL, '지민찡', '선물용으로 보내려는데 보자기 포장이 가능할까요? 받는 분 주소로 바로 보내고 싶어요.', '보자기 포장 무료로 해 드려요. 주문 시 배송 메모에 "선물 포장"이라고 남겨 주세요.', TRUE, TIMESTAMP(@today - INTERVAL 8 DAY, '16:00:00'), TIMESTAMP(@today - INTERVAL 9 DAY, '13:00:00')),
  (4, 104, 103, 1, '유기농 현미 4kg (2026년 햅쌀)', NULL, '유나', '7분도로 도정하면 밥 지을 때 따로 불려야 하나요?', NULL, FALSE, NULL, TIMESTAMP(@today - INTERVAL 2 DAY, '13:00:00')),
  (5, 4, 201, 2, '분청 손잡이 머그 350ml', NULL, '서연이네', '식기세척기 · 전자레인지 사용해도 괜찮나요?', '무연유약이라 둘 다 사용 가능합니다. 다만 흑유는 광택 유지를 위해 손세척을 권해 드려요.', FALSE, TIMESTAMP(@today - INTERVAL 10 DAY, '16:00:00'), TIMESTAMP(@today - INTERVAL 12 DAY, '13:00:00')),
  (6, 6, 203, 2, '물레 성형 미니 화병', NULL, '지민찡', '생화를 꽂아 두면 물이 스며 나오지 않나요? 원목 선반 위에 두려고 해요.', NULL, TRUE, NULL, TIMESTAMP(@today - INTERVAL 3 DAY, '13:00:00')),
  (7, 5, 204, 2, '다완 찻잔 2인 세트', NULL, '도현', '찻잔 두 개 모양이 서로 다른가요? 선물용이라 짝이 맞았으면 해요.', '손으로 빚어 모양이 조금씩 다르지만 같은 흙 · 유약으로 한 번에 구워 짝을 맞춰 보내드립니다.', FALSE, TIMESTAMP(@today - INTERVAL 4 DAY, '16:00:00'), TIMESTAMP(@today - INTERVAL 5 DAY, '13:00:00'));
