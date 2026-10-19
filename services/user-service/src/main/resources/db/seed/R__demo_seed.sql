-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션)
-- 목업(gotgan_expage/src/data/*.js) 데이터를 INSERT 로 옮긴다. 담당 perish95
--
-- 시연 계정 6개 — Keycloak 시드(gotgan-config scripts/keycloak-seed.sh)와 **이메일 · user_id 가 같아야 한다**
--   gateway 가 토큰의 user_id 클레임으로 X-User-Id 를 만들고, 서비스는 그 값으로 이 테이블을 조회한다
--   판매자 store_id(1 · 2)는 Keycloak 속성에만 있고 storedb 시드(store 담당)와 맞춘다
-- keycloak_id 는 Keycloak 이 매일 새 UUID 로 만들어 미리 알 수 없다 → NULL. user-service 가 이메일로 찾아 연결한다
-- 비밀번호는 Keycloak 에만 있다 (GitHub Secret SEED_ADMIN_PASSWORD · SEED_DEMO_PASSWORD)
-- 재실행 안전: user_id 가 이미 있으면 프로필 값만 시드 값으로 되돌린다 (포인트 · 상태 등 시연 중 바뀐 값은 유지)

INSERT INTO users (user_id, email, name, nick, phone, signup_provider, grade, status)
VALUES
  (1, 'wlsgksvheh+admin@gmail.com',   '곳간 관리자', '관리자',  '010-0000-0001', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (2, 'wlsgksvheh+seller1@gmail.com', '판매자1',     '판매자1', '010-0000-0002', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (3, 'wlsgksvheh+seller2@gmail.com', '판매자2',     '판매자2', '010-0000-0003', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (4, 'wlsgksvheh+buyer1@gmail.com',  '구매자1',     '구매자1', '010-0000-0004', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (5, 'wlsgksvheh+buyer2@gmail.com',  '구매자2',     '구매자2', '010-0000-0005', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (6, 'wlsgksvheh+buyer3@gmail.com',  '구매자3',     '구매자3', '010-0000-0006', 'LOCAL', 'NORMAL', 'ACTIVE')
AS seed
ON DUPLICATE KEY UPDATE
  email = seed.email,
  name  = seed.name,
  nick  = seed.nick,
  phone = seed.phone;
