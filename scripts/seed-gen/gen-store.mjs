// storedb 시드 생성 — node scripts/seed-gen/gen-store.mjs → services/store-service/src/main/resources/db/seed/R__demo_seed.sql
// 직접 고치지 말고 scenario.mjs 를 고친 뒤 다시 생성한다. 담당 구지훈 (파일 주인 store 박지연 — 리뷰)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CATEGORY, GRADE, REPRESENTATIVE, STORES } from './scenario.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = path.join(ROOT, 'services/store-service/src/main/resources/db/seed/R__demo_seed.sql');

const q = (v) => (v === null || v === undefined ? 'NULL' : typeof v === 'number' ? String(v) : `'${String(v).replace(/'/g, "''")}'`);
const ago = (days) => `@today - INTERVAL ${days} DAY + INTERVAL 10 HOUR`; // 그날 10:00 KST
const json = (items) => `JSON_ARRAY(${items.map((p) => `JSON_OBJECT('name', ${q(p.name)}, 'subCategory', ${q(p.subCategory)}, 'price', ${p.price})`).join(', ')})`;

const apps = STORES.map((s) => `  (${s.storeId}, ${s.userId}, ${CATEGORY[s.cat]}, 'INDIVIDUAL', ${q(s.name)}, ${q(s.owner)}, ${q(s.bizNo)}, ${q(`2026-시연-${String(s.storeId).padStart(4, '0')}`)},
   ${q(s.phone)}, ${q(s.email)}, '서울 성동구 성수이로 ${s.storeId}', ${q(s.name)}, ${q(s.slug)}, ${q(`<p>${s.intro}</p>`)},
   ${json(REPRESENTATIVE[s.cat])}, 'FROM_5M_TO_20M', NULL, '곳간은행', ${q(`100-0000-${String(s.storeId).padStart(6, '0')}`)}, ${q(s.owner)},
   'APPROVED', FALSE, ${ago(s.since + 3)}, ${ago(s.since)}, ${ago(s.since + 3)})`);

const appHistory = STORES.flatMap((s) => [
  `  (${s.storeId * 10 + 1}, ${s.storeId}, NULL, 'PENDING', 'APPLICANT', NULL, ${ago(s.since + 3)})`,
  `  (${s.storeId * 10 + 2}, ${s.storeId}, 'PENDING', 'APPROVED', 'ADMIN', NULL, ${ago(s.since)})`,
]);

const stores = STORES.map((s) => `  (${s.storeId}, ${s.storeId}, ${s.userId}, ${q(s.name)}, ${q(s.slug)}, ${q(`<p>${s.intro}</p>`)}, ${q(s.color)}, ${q(s.phone)},
   ${s.shipFee}, ${q(s.freeOver)}, ${q(s.courier)}, ${q(s.dispatch)}, ${q(GRADE[s.grade])}, ${q(s.status)}, ${q(s.statusReason ?? null)}, ${ago(s.since)})`);

const storeHistory = STORES.flatMap((s) => [
  `  (${s.storeId * 10 + 1}, ${s.storeId}, NULL, 'ACTIVE', 'ADMIN', NULL, ${ago(s.since)})`,
  ...(s.status === 'SUSPENDED' ? [`  (${s.storeId * 10 + 2}, ${s.storeId}, 'ACTIVE', 'SUSPENDED', 'ADMIN', ${q(s.statusReason)}, ${ago(s.suspendedDaysAgo)})`] : []),
]);

const sql = `-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션). 담당 VoC-100
-- ⚠️ 생성 파일 — 직접 고치지 말고 gotgan-backend scripts/seed-gen/scenario.mjs 를 고친 뒤 \`node scripts/seed-gen/gen-store.mjs\` (구지훈)
--
-- 목업 스토어 9개(프론트 mocks/fixtures/catalog.js) — 모두 승인된 입점 신청서(store_application, FK)에서 생긴 상태로 신청서 · 이력도 함께
--   store_id = 10 + 목업 번호. 단 시연 판매자 로그인 스토어 1 · 2 는 Keycloak 시드 · settlement 시드와 번호가 같아야 해서
--   wlsgksvheh+seller1@gmail.com  user_id 2 → store_id 1 (산골농부 박씨네, 식품)
--   wlsgksvheh+seller2@gmail.com  user_id 3 → store_id 2 (토담공방, 리빙)
--   나머지 대표자는 user_id 200 + 목업 번호(user 시드 — 조회용, 로그인 불가)
-- category_id 는 productdb 카테고리 시드(1~6)와 같은 번호. 신청서 id = 스토어 id, 이력 id = 스토어 id × 10 + 순번
-- 볕드는집(15)은 정지 상태 — 관리자 스토어 화면 · settlement 자동 보류 시연용
-- 사업자 · 계좌 정보는 시연용 가짜 값. 서류(application_document)는 S3 객체가 없어 넣지 않는다. 이벤트는 발행되지 않는다
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 바뀐 스토어 정보 · 상태는 유지

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);   -- KST 오늘 (DB 시간대와 무관)

INSERT IGNORE INTO store_application
  (store_application_id, user_id, category_id, biz_type, company_name, owner_name, biz_reg_no, ecommerce_reg_no,
   manager_phone, manager_email, business_address, store_name, slug, intro,
   representative_products, expected_monthly_sales, sales_channel, bank_name, account_no, account_holder,
   status, marketing_agreed, terms_agreed_at, status_changed_at, created_at)
VALUES
${apps.join(',\n')};

INSERT IGNORE INTO application_history (application_history_id, store_application_id, from_status, to_status, actor_type, reason, created_at) VALUES
${appHistory.join(',\n')};

INSERT IGNORE INTO store
  (store_id, store_application_id, user_id, store_name, slug, intro, color, cs_phone,
   shipping_fee, free_shipping_threshold, courier, dispatch_note, grade, status, status_reason, created_at)
VALUES
${stores.join(',\n')};

INSERT IGNORE INTO store_status_history (store_status_history_id, store_id, from_status, to_status, actor_type, reason, created_at) VALUES
${storeHistory.join(',\n')};
`;

fs.writeFileSync(OUT, sql);
console.log(`${path.relative(ROOT, OUT)} — 신청서 ${apps.length} · 신청 이력 ${appHistory.length} · 스토어 ${stores.length} · 스토어 이력 ${storeHistory.length}`);
