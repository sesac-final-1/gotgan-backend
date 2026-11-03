// 시연 시드 생성 — node scripts/seed-gen/gen.mjs → 서비스별 db/seed/R__demo_seed.sql (user · store · product · order · payment · settlement · review · admin)
// 직접 고치지 말고 scenario.mjs 를 고친 뒤 다시 생성한다. 담당 구지훈 (각 파일 주인이 리뷰)
// 금액 · 수수료 · 포인트는 서버 규칙 그대로 계산: 판매수수료(상위 카테고리율) · 결제수수료 2.2% 를 상품주문마다 원 단위 내림,
// 적립 = (상품주문 금액 − 사용 포인트) × 5% 내림 — 구매확정 때만(ref_key earn-{상품주문}, 시각 = 구매확정), 배송비는 스토어 정책(무료 기준 이상이면 0)
// 날짜 순서(스토어 개설 < 상품 등록 < 주문 < 구매확정 < 스토어 정지 < 주간 집계 < 오늘) · 등급 = 누적 금액 기준은 아래 check 로 생성 때 검증
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  ADDRESS, APPLICATIONS, CATALOG, CATEGORY, CATEGORY_TREE, COMMISSION, EARN_RATE, GRADE, INQUIRIES, LOGIN_BUYERS, MODERATIONS, ORDERS, PAYMENT_FEE_RATE, PRODUCTS,
  REPRESENTATIVE, REVIEWS, SAMPLE_DOCS, STORES, VIEW_BUYERS,
  combos, gradeOf, salePrice, subCategoryId,
} from './scenario.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const q = (v) => (v === null || v === undefined ? 'NULL' : typeof v === 'number' ? String(v) : typeof v === 'boolean' ? String(v).toUpperCase()
  : `'${String(v).replace(/'/g, "''")}'`);
const ago = (days, time = '10:00:00') => `TIMESTAMP(@today - INTERVAL ${days} DAY, '${time}')`;
const json = (items) => `JSON_ARRAY(${items.map((p) => `JSON_OBJECT('name', ${q(p.name)}, 'subCategory', ${q(p.subCategory)}, 'price', ${p.price})`).join(', ')})`;
const mask = (name) => (name.length <= 2 ? `${name[0]}*` : `${name[0]}${'*'.repeat(name.length - 2)}${name.at(-1)}`);
const storeById = Object.fromEntries(STORES.map((s) => [s.storeId, s]));
const check = (ok, message) => { if (!ok) throw new Error(`시드 검증 실패: ${message}`); };
// 스토어 정지 시각 — 지난주 월요일(@w1) 기준 요일. 오늘 기준 daysAgo 를 쓰면 요일마다 구매확정 · 집계와 앞뒤가 바뀐다
const suspendedAt = (st) => `TIMESTAMP(@w1 + INTERVAL ${st.suspendedW1Day} DAY, '10:00:00')`;
const buyerById = Object.fromEntries(LOGIN_BUYERS.map((b) => [b.userId, b]));
const header = (owner, extra) => `-- 시연용 시드 (local · k8s 프로필에서만 실행, 내용이 바뀌면 재실행 — R__ 반복 마이그레이션). 담당 ${owner}
-- ⚠️ 생성 파일 — 직접 고치지 말고 gotgan-backend scripts/seed-gen/scenario.mjs 를 고친 뒤 \`node scripts/seed-gen/gen.mjs\` (구지훈)
-- 서비스끼리 같은 번호 · 금액 · 날짜를 가리킨다(store_id · user_id · order_item_id · 주문번호). 날짜는 실행일 기준 상대값(KST)
${extra}
-- 재실행 안전: PK 가 이미 있으면 건너뜀(INSERT IGNORE) — 시연 중 바뀐 값은 유지

SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);            -- KST 오늘 (DB 시간대와 무관)
SET @w1    := @today - INTERVAL (WEEKDAY(@today) + 7) DAY;        -- 지난주 월요일
`;
const write = (service, sql) => {
  const file = path.join(ROOT, `services/${service}-service/src/main/resources/db/seed/R__demo_seed.sql`);
  fs.writeFileSync(file, sql);
  return path.relative(ROOT, file);
};
const insert = (table, cols, rows) => `INSERT IGNORE INTO ${table} (${cols.join(', ')}) VALUES\n${rows.map((r) => `  (${r.join(', ')})`).join(',\n')};\n`;

// ── 주문 계산 (모든 서비스의 기준)
const orders = ORDERS.map((o, i) => {
  const id = 1001 + i;
  const p = PRODUCTS[o.product];
  const store = storeById[p.storeId];
  const itemAmt = p.price * o.qty;
  const shippingFee = store.freeOver && itemAmt >= store.freeOver ? 0 : store.shipFee;
  const topCategory = CATEGORY[store.cat];
  // 시각: 구매확정 주(week · day)에서 거꾸로, 진행 중은 오늘에서 거꾸로 — 결제 < 발송 < 배송완료 < 구매확정(배송완료 + 7일)
  let t;
  if (o.week) {
    const conf = `TIMESTAMP(@w1 - INTERVAL ${(o.week - 1) * 7} DAY + INTERVAL ${o.day} DAY, '10:00:00')`;
    t = { status: 'CONFIRMED', paid: `${conf} - INTERVAL 9 DAY`, shipped: `${conf} - INTERVAL 8 DAY`, delivered: `${conf} - INTERVAL 7 DAY`, confirmed: conf };
  } else {
    const back = { PAYMENT_DONE: 1, PREPARING: 2, SHIPPING: 3, DELIVERED: 5 }[o.recent];
    t = { status: o.recent, paid: ago(back, '11:00:00') };
    if (['SHIPPING', 'DELIVERED'].includes(o.recent)) t.shipped = ago(back - 1, '15:00:00');
    if (o.recent === 'DELIVERED') t.delivered = ago(back - 2, '14:00:00');
  }
  const orderNo = `CONCAT(DATE_FORMAT(${t.paid}, '%Y%m%d'), '-${900000 + id}')`;
  const confirmDay = o.week ? -(o.week - 1) * 7 + o.day : null;   // @w1 기준 구매확정 일
  return { ...o, id, p, store, itemAmt, shippingFee, payAmt: itemAmt + shippingFee, topCategory, t, orderNo, confirmDay,
    earned: Math.floor((itemAmt * EARN_RATE) / 100), buyerInfo: buyerById[o.buyer] };
});

// ── 검증 — 날짜 순서 · 상태
const catalogById = Object.fromEntries(CATALOG.map((p) => [p.id, p]));
for (const o of orders) {
  check(!o.recent || ['PAYMENT_DONE', 'PREPARING'].includes(o.recent), `상품주문 ${o.id}: 진행 중 시드는 결제완료 · 배송준비까지 (배송중 · 배송완료는 스케줄러가 바로 넘김)`);
  // 결제일(오늘 기준 최대 며칠 전) — 구매확정 주문은 확정 9일 전, 확정은 최대 (week × 7 + 6)일 전
  const paidDaysAgo = o.week ? o.week * 7 + 6 + 9 : 2;
  check(catalogById[o.product].created > paidDaysAgo, `상품 ${o.product}: 등록일(${catalogById[o.product].created}일 전)이 주문(최대 ${paidDaysAgo}일 전)보다 늦음`);
}
for (const p of CATALOG) {
  const st = storeById[Math.floor(p.id / 100)];
  check(p.created <= st.since, `상품 ${p.id}: 스토어 ${st.storeId} 개설(${st.since}일 전)보다 먼저 등록(${p.created}일 전)`);
}
for (const st of STORES.filter((x) => x.status !== 'ACTIVE')) {
  const last = Math.max(...orders.filter((o) => o.store.storeId === st.storeId && o.confirmDay !== null).map((o) => o.confirmDay));
  check(st.suspendedW1Day > last && st.suspendedW1Day < 7, `스토어 ${st.storeId}: 정지(@w1+${st.suspendedW1Day})는 마지막 구매확정(@w1+${last}) 뒤 · 주간 집계(@w1+7) 전`);
}

// ── user
const buyerTotals = Object.fromEntries(LOGIN_BUYERS.map((b) => [b.userId, { point: 0, confirmed: 0 }]));
for (const o of orders.filter((x) => x.t.status === 'CONFIRMED')) {
  buyerTotals[o.buyer].point += o.earned;
  buyerTotals[o.buyer].confirmed += o.itemAmt;
}
for (const b of LOGIN_BUYERS) buyerTotals[b.userId].spent = b.baseSpent + buyerTotals[b.userId].confirmed;
for (const b of VIEW_BUYERS) check(b.grade === gradeOf(b.spent), `회원 ${b.userId}: 등급 ${b.grade} ↔ 누적 ${b.spent} (기준 ${gradeOf(b.spent)})`);
const userCols = ['user_id', 'email', 'name', 'nick', 'phone', 'signup_provider', 'point_balance', 'total_confirmed_amt', 'grade', 'status',
  'status_reason', 'suspended_at', 'suspended_until', 'last_login_at', 'created_at'];
const owners = STORES.filter((s) => s.userId >= 200);
const userRows = [
  ...LOGIN_BUYERS.map((b) => [b.userId, q(b.email), q(b.name), q(b.nick), q(b.phone), "'LOCAL'", buyerTotals[b.userId].point, buyerTotals[b.userId].spent,
    q(gradeOf(buyerTotals[b.userId].spent)), "'ACTIVE'", 'NULL', 'NULL', 'NULL', ago(b.login, '09:00:00'), ago(b.joined)]),
  ...VIEW_BUYERS.map((b) => [b.userId, q(b.email), q(b.name), q(b.nick), q(b.phone), "'LOCAL'", b.point, b.spent, q(b.grade),
    q(b.suspended ? 'SUSPENDED' : 'ACTIVE'), q(b.suspended?.reason ?? null), b.suspended ? ago(b.suspended.daysAgo) : 'NULL',
    b.suspended ? ago(b.suspended.daysAgo - b.suspended.days) : 'NULL', ago(b.login, '09:00:00'), ago(b.joined)]),
  ...owners.map((s) => [s.userId, q(s.email), q(s.owner), 'NULL', q(s.phone), "'LOCAL'", 0, 0, "'NORMAL'", "'ACTIVE'",
    'NULL', 'NULL', 'NULL', ago(1, '09:00:00'), ago(s.since + 3)]),
];
// 구매확정 적립 — 서버(OrderItemConfirmed 소비)와 같은 ref_key earn-{상품주문} · 시각 = 구매확정. 잔액은 확정 시각 순으로 누적
//   같은 키라 시드 뒤 이벤트가 다시 와도 (type, ref_key) 유니크로 두 번 적립되지 않는다
const earnedOrders = orders.filter((o) => o.t.status === 'CONFIRMED').sort((a, b) => a.confirmDay - b.confirmDay || a.id - b.id);
const balance = {};
const pointRows = [
  ...earnedOrders.map((o) => {
    balance[o.buyer] = (balance[o.buyer] ?? 0) + o.earned;
    return [o.id, o.buyer, o.id, o.orderNo, "'EARN'", o.earned, 0, balance[o.buyer], q(`earn-${o.id}`), o.t.confirmed];
  }),
  ...VIEW_BUYERS.filter((b) => b.point > 0).map((b) => [b.userId * 10, b.userId, 'NULL', 'NULL', "'EARN'", b.point, 0, b.point, q(`seed-base-${b.userId}`),
    ago(Math.min(b.joined, 30))]),
];
for (const b of LOGIN_BUYERS) check((balance[b.userId] ?? 0) === buyerTotals[b.userId].point, `회원 ${b.userId}: 포인트 잔액 ↔ 적립 이력 합`);
const userSql = `${header('perish95', `-- 1 관리자 · 2 · 3 판매자 · 4~6 구매자 = Keycloak 시드 계정(이메일 · user_id 고정 — gotgan-config scripts/keycloak-seed.sh). keycloak_id 는 NULL(이메일로 연결)
-- 4~6 은 목업 u1~u3 프로필, 포인트 = 구매확정분 적립(잔액 = 적립 이력 합) · 누적 구매확정 = 시드 이전 누적 + 시드 확정분 · 등급 = 누적 기준(30만 · 80만)
-- 104~114 목업 구매자 · 201~ 스토어 대표자 = 조회용(Keycloak 계정 없음 — 로그인 불가). 111 은 이용정지(관리자 회원 화면 시연)`)}
-- 시연 계정 — 프로필은 시드 값으로 되돌리고 포인트 · 상태는 시연 중 바뀐 값 유지
-- 가입 · 최근 접속 시각 = 스토어 개설 전 · 어제 (값이 없으면 관리자 회원 목록의 날짜 칸이 비고, 가입일이 매일 오늘로 보임)
INSERT INTO users (user_id, email, name, nick, phone, signup_provider, grade, status, last_login_at, created_at)
VALUES
  (1, 'wlsgksvheh+admin@gmail.com',   '곳간 관리자', '관리자', '010-0000-0001', 'LOCAL', 'NORMAL', 'ACTIVE', ${ago(1, '09:00:00')}, ${ago(1400)}),
  (2, 'wlsgksvheh+seller1@gmail.com', ${q(storeById[1].owner)}, NULL, ${q(storeById[1].phone)}, 'LOCAL', 'NORMAL', 'ACTIVE', ${ago(1, '09:00:00')}, ${ago(storeById[1].since + 3)}),
  (3, 'wlsgksvheh+seller2@gmail.com', ${q(storeById[2].owner)}, NULL, ${q(storeById[2].phone)}, 'LOCAL', 'NORMAL', 'ACTIVE', ${ago(1, '09:00:00')}, ${ago(storeById[2].since + 3)})
AS seed
ON DUPLICATE KEY UPDATE email = seed.email, name = seed.name, nick = seed.nick, phone = seed.phone;

${insert('users', userCols, userRows)}
${insert('point_history', ['point_history_id', 'user_id', 'order_item_id', 'order_no', 'type', 'amount', 'shortfall_amt', 'balance_after', 'ref_key', 'created_at'], pointRows)}
-- 로그인 구매자 기본 배송지 1개(address_id = user_id) — 주문 시드 배송지와 같은 주소
${insert('address', ['address_id', 'user_id', 'label', 'receiver_name', 'receiver_phone', 'zipcode', 'address1', 'address2', 'is_default', 'created_at'],
  LOGIN_BUYERS.map((b) => [b.userId, b.userId, q(ADDRESS.label), q(b.name), q(b.phone), q(ADDRESS.zipcode), q(ADDRESS.address1), q(`${b.userId}0${b.userId}호`), 'TRUE', ago(b.joined)]))}`;

// ── store
const storeSql = `${header('VoC-100', `-- 목업 스토어 9개(프론트 mocks/fixtures/catalog.js) — 모두 승인된 입점 신청서에서 생긴 상태로 신청서 · 이력도 함께
--   store_id = 10 + 목업 번호. 단 시연 판매자 로그인 스토어 1 · 2 는 Keycloak 시드 · settlement 시드와 번호가 같아야 해서
--   seller1 user 2 → store 1 (산골농부 박씨네, 식품) · seller2 user 3 → store 2 (토담공방, 리빙). 나머지 대표자는 user 200 + 목업 번호(조회용)
-- category_id = productdb 카테고리 시드(1~6). 신청서 id = 스토어 id, 이력 id = 스토어 id × 10 + 순번. 볕드는집(15)은 정지
-- 심사 시연용 신청 101(심사대기, buyer3 user 6) · 102(보완요청, buyer2 user 5 — 4일 전 신청 · 2일 전 보완요청). 스토어 없음
-- 사업자 · 계좌는 가짜 값. 서류는 모든 신청서가 같은 샘플(seller-docs applications/seed/ — scripts/seed-gen/sample-docs), 문서 id = 신청서 id × 10 + 순번
-- 102 보완요청은 다시 낼 서류(requested_docs) 지정. 이벤트는 발행되지 않는다`)}
${insert('store_application', ['store_application_id', 'user_id', 'category_id', 'biz_type', 'company_name', 'owner_name', 'biz_reg_no', 'ecommerce_reg_no',
  'manager_phone', 'manager_email', 'business_address', 'store_name', 'slug', 'intro', 'representative_products', 'expected_monthly_sales', 'sales_channel',
  'bank_name', 'account_no', 'account_holder', 'status', 'status_reason', 'requested_docs', 'marketing_agreed', 'terms_agreed_at', 'status_changed_at', 'created_at'],
STORES.map((s) => [s.storeId, s.userId, CATEGORY[s.cat], "'INDIVIDUAL'", q(s.name), q(s.owner), q(s.bizNo), q(`2026-시연-${String(s.storeId).padStart(4, '0')}`),
  q(s.phone), q(s.email), q(`서울 성동구 성수이로 ${s.storeId}`), q(s.name), q(s.slug), q(`<p>${s.intro}</p>`), json(REPRESENTATIVE[s.cat]), "'FROM_5M_TO_20M'", 'NULL',
  "'곳간은행'", q(`100-0000-${String(s.storeId).padStart(6, '0')}`), q(s.owner), "'APPROVED'", 'NULL', 'NULL', 'FALSE', ago(s.since + 3), ago(s.since), ago(s.since + 3)])
  .concat(APPLICATIONS.map((a) => [a.applicationId, a.userId, CATEGORY[a.cat], "'INDIVIDUAL'", q(a.name), q(a.owner), q(a.bizNo), q(`2026-시연-${a.applicationId}`),
    q(a.phone), q(a.email), q(`서울 성동구 성수이로 ${a.applicationId}`), q(a.name), q(a.slug), q(`<p>${a.intro}</p>`), json(a.products ?? REPRESENTATIVE[a.cat]), "'UNDER_5M'", 'NULL',
    "'곳간은행'", q(`100-0000-${String(a.applicationId).padStart(6, '0')}`), q(a.owner), q(a.status), q(a.supplement?.reason ?? null), a.supplement?.docs ? `JSON_ARRAY(${a.supplement.docs.map(q).join(', ')})` : 'NULL', 'FALSE',
    ago(a.submittedDaysAgo), ago(a.supplement?.daysAgo ?? a.submittedDaysAgo), ago(a.submittedDaysAgo)])))}
${insert('application_document', ['application_document_id', 'store_application_id', 'doc_type', 's3_key', 'original_name', 'content_type', 'file_size', 'is_current', 'created_at'],
  [...STORES.map((x) => [x.storeId, ago(x.since + 3)]), ...APPLICATIONS.map((a) => [a.applicationId, ago(a.submittedDaysAgo)])]
    .flatMap(([id, at]) => SAMPLE_DOCS.map((d, i) => [id * 10 + i + 1, id, q(d.docType), q(d.key), q(d.name), q(d.type), d.size, 'TRUE', at])))}
${insert('application_history', ['application_history_id', 'store_application_id', 'from_status', 'to_status', 'actor_type', 'reason', 'created_at'],
  STORES.flatMap((s) => [[s.storeId * 10 + 1, s.storeId, 'NULL', "'PENDING'", "'APPLICANT'", 'NULL', ago(s.since + 3)],
    [s.storeId * 10 + 2, s.storeId, "'PENDING'", "'APPROVED'", "'ADMIN'", 'NULL', ago(s.since)]])
  .concat(APPLICATIONS.flatMap((a) => [[a.applicationId * 10 + 1, a.applicationId, 'NULL', "'PENDING'", "'APPLICANT'", 'NULL', ago(a.submittedDaysAgo)],
    ...(a.supplement ? [[a.applicationId * 10 + 2, a.applicationId, "'PENDING'", "'SUPPLEMENT'", "'ADMIN'", q(a.supplement.reason), ago(a.supplement.daysAgo)]] : [])])))}
${insert('store', ['store_id', 'store_application_id', 'user_id', 'store_name', 'slug', 'intro', 'color', 'cs_phone', 'shipping_fee', 'free_shipping_threshold',
  'courier', 'dispatch_note', 'grade', 'status', 'status_reason', 'created_at'],
STORES.map((s) => [s.storeId, s.storeId, s.userId, q(s.name), q(s.slug), q(`<p>${s.intro}</p>`), q(s.color), q(s.phone), s.shipFee, q(s.freeOver),
  q(s.courier), q(s.dispatch), q(GRADE[s.grade]), q(s.status), q(s.statusReason ?? null), ago(s.since)]))}
${insert('store_status_history', ['store_status_history_id', 'store_id', 'from_status', 'to_status', 'actor_type', 'reason', 'created_at'],
  STORES.flatMap((s) => [[s.storeId * 10 + 1, s.storeId, 'NULL', "'ACTIVE'", "'ADMIN'", 'NULL', ago(s.since)],
    ...(s.status === 'SUSPENDED' ? [[s.storeId * 10 + 2, s.storeId, "'ACTIVE'", "'SUSPENDED'", "'ADMIN'", q(s.statusReason), suspendedAt(s)]] : [])]))}`;

// ── order
const flow = ['PAYMENT_DONE', 'PREPARING', 'SHIPPING', 'DELIVERED', 'CONFIRMED'];
const historyRows = orders.flatMap((o) => {
  const upto = flow.indexOf(o.t.status);
  const rows = [[o.id * 10 + 1, o.id, o.buyer, 'NULL', "'ORDERED'", "'BUYER'", 'NULL', o.t.paid],
    [o.id * 10 + 2, o.id, 'NULL', "'ORDERED'", "'PAYMENT_DONE'", "'SYSTEM'", 'NULL', o.t.paid]];
  const at = { PREPARING: `${o.t.paid} + INTERVAL 3 HOUR`, SHIPPING: o.t.shipped, DELIVERED: o.t.delivered, CONFIRMED: o.t.confirmed };
  const actor = { PREPARING: ['SELLER', o.store.userId], SHIPPING: ['SELLER', o.store.userId], DELIVERED: ['SYSTEM', null], CONFIRMED: ['SYSTEM', null] };   // 확정 = 배송완료 + 7일 자동
  for (let k = 1; k <= upto; k += 1) {
    const to = flow[k];
    rows.push([o.id * 10 + 2 + k, o.id, q(actor[to][1]), q(flow[k - 1]), q(to), q(actor[to][0]), 'NULL', at[to]]);
  }
  return rows;
});
const orderSql = `${header('perish95', `-- 주문 = 스토어 1곳 · 상품 1개 · 포인트 사용 없음. order_id = order_item_id = payment_id, 주문번호 = 결제일-900000+id (실제 주문 번호대와 겹치지 않게)
-- 구매확정분은 settlement 시드(정산서 · 항목)와 같은 order_item_id · 금액, 적립은 user 시드 point_history 와 같은 금액
-- 상품 · 옵션 id 는 product 시드 예정 번호(스토어 × 100 + 순번 · 상품 × 100 + 1). Saga · 아웃박스 행은 넣지 않는다(이미 끝난 주문)`)}
${insert('orders', ['order_id', 'buyer_id', 'order_no', 'orderer_name', 'orderer_display_name', 'orderer_email', 'receiver_name', 'receiver_phone', 'zipcode',
  'address1', 'address2', 'delivery_memo', 'total_product_amt', 'total_shipping_fee', 'used_point', 'pay_amt', 'status', 'paid_at', 'created_at'],
orders.map((o) => [o.id, o.buyer, o.orderNo, q(o.buyerInfo.name), q(mask(o.buyerInfo.name)), q(o.buyerInfo.email), q(o.buyerInfo.name), q(o.buyerInfo.phone),
  "'04790'", "'서울 성동구 왕십리로 1'", q(`${o.buyer}0${o.buyer}호`), "'문 앞에 놓아주세요'", o.itemAmt, o.shippingFee, 0, o.payAmt, "'PAID'", o.t.paid, o.t.paid]))}
${insert('order_store_shipment', ['order_store_shipment_id', 'order_id', 'store_id', 'shipping_fee', 'created_at'],
  orders.map((o) => [o.id, o.id, o.store.storeId, o.shippingFee, o.t.paid]))}
${insert('order_item', ['order_item_id', 'order_id', 'store_id', 'product_id', 'product_option_id', 'category_id', 'top_category_id', 'item_no', 'store_name',
  'product_name', 'option_name', 'unit_price', 'quantity', 'item_amt', 'used_point', 'earned_point', 'courier', 'tracking_no', 'status', 'shipped_at', 'delivered_at',
  'confirmed_at', 'created_at'],
orders.map((o) => [o.id, o.id, o.store.storeId, o.product, o.product * 100 + 1, o.p.category, o.topCategory, `CONCAT(${o.orderNo}, '-01')`, q(o.store.name),
  q(o.p.name), q(o.p.optionName), o.p.price, o.qty, o.itemAmt, 0, o.earned, o.t.shipped ? q(o.store.courier) : 'NULL',
  o.t.shipped ? q(`6512${String(o.id).padStart(8, '0')}`) : 'NULL', q(o.t.status), o.t.shipped ?? 'NULL', o.t.delivered ?? 'NULL', o.t.confirmed ?? 'NULL', o.t.paid]))}
${insert('order_item_history', ['order_item_history_id', 'order_item_id', 'actor_id', 'from_status', 'to_status', 'actor_type', 'reason', 'created_at'], historyRows)}`;

// ── payment
const paymentSql = `${header('jihoon-devstu', `-- 주문 시드와 같은 결제(payment_id = order_id, 금액 = pay_amt). portone_payment_id 는 seed- 로 시작 — 포트원에 실제 결제가 없다
-- (시드 주문을 취소 · 환불하면 포트원 조회가 실패 — 시연은 새로 결제한 주문으로)`)}
${insert('payment', ['payment_id', 'order_id', 'order_no', 'portone_payment_id', 'amount', 'cancelled_amount', 'pg_tx_id', 'method', 'status', 'paid_at', 'created_at'],
  orders.map((o) => [o.id, o.id, o.orderNo, `CONCAT('seed-pay-', ${o.orderNo})`, o.payAmt, 0, 'NULL', "'CARD'", "'PAID'", o.t.paid, o.t.paid]))}`;

// ── settlement — 구매확정분을 스토어 × 주차로 묶음. 지난주 = 지급예정(정지 스토어는 자동 보류), 지지난주 = 지급 완료
const confirmed = orders.filter((o) => o.t.status === 'CONFIRMED').map((o) => {
  const rate = COMMISSION[o.topCategory];
  const commission = Math.floor((o.itemAmt * rate) / 100);
  const fee = Math.floor((o.itemAmt * PAYMENT_FEE_RATE) / 100);
  return { ...o, rate, commission, fee, payout: o.itemAmt - commission - fee };
});
const groups = [];
for (const o of confirmed) {
  let g = groups.find((x) => x.storeId === o.store.storeId && x.week === o.week);
  if (!g) groups.push((g = { storeId: o.store.storeId, week: o.week, store: o.store, items: [] }));
  g.items.push(o);
}
groups.sort((a, b) => a.storeId - b.storeId || a.week - b.week);
groups.forEach((g, i) => {
  g.id = i + 1;
  g.start = `@w1 - INTERVAL ${(g.week - 1) * 7} DAY`;
  g.held = g.week === 1 && g.store.status !== 'ACTIVE';
  g.status = g.week === 2 ? 'PAID' : g.held ? 'HELD' : 'SCHEDULED';
  g.sum = (k) => g.items.reduce((a, x) => a + x[k], 0);
});
const aggregatedAt = (g) => `TIMESTAMP(${g.start} + INTERVAL 7 DAY, '03:00:00')`;
const paidAt = (g) => `TIMESTAMP(${g.start} + INTERVAL 10 DAY, '10:00:00')`;
const settlementHistory = groups.flatMap((g) => [
  [g.id * 10 + 1, g.id, 'NULL', "'SCHEDULED'", "'SYSTEM'", "'주간 집계'", aggregatedAt(g)],
  ...(g.status === 'PAID' ? [[g.id * 10 + 2, g.id, "'SCHEDULED'", "'PAID'", "'ADMIN'", 'NULL', paidAt(g)]] : []),
  ...(g.held ? [[g.id * 10 + 2, g.id, "'SCHEDULED'", "'HELD'", "'SYSTEM'", "'스토어 정지 · 폐점 — 자동 보류'", aggregatedAt(g)]] : []),
]);
const settlementSql = `${header('jihoon-devstu', `-- 정산 = order 시드의 구매확정 상품주문(order_item_id · 금액 같음)을 스토어 × 주차로 묶어 서버 규칙으로 계산
--   수수료 = 상품주문마다 내림(상위 카테고리율 · 결제 ${PAYMENT_FEE_RATE}%), 지난주 = 지급예정(정지 스토어는 자동 보류), 지지난주 = 지급 완료 + 지급 기록
-- settlement_store = store 시드 스냅샷(StoreApproved 로 받는 값 — 스토어명 · 판매자 이메일 · 계좌 · 상태)`)}
-- 상위 카테고리 판매수수료율 — 상위 ID 1~6 은 product 시드와 같은 번호, effective_from 은 시드 구매확정일보다 앞서야 함
INSERT IGNORE INTO category_commission (category_id, commission_rate, effective_from) VALUES
${Object.entries(COMMISSION).map(([c, r]) => `  (${c}, ${r.toFixed(2)}, '2026-01-01')`).join(',\n')};

${insert('settlement_store', ['settlement_store_id', 'store_id', 'store_name', 'seller_email', 'bank_name', 'account_number', 'account_holder', 'store_status',
  'store_status_changed_at'],
  STORES.map((s) => [s.storeId, s.storeId, q(s.name), q(s.email), "'곳간은행'", q(`100-0000-${String(s.storeId).padStart(6, '0')}`), q(s.owner), q(s.status),
    s.status === 'ACTIVE' ? 'NULL' : suspendedAt(s)]))}
${insert('settlement', ['settlement_id', 'store_id', 'week_start', 'week_end', 'item_count', 'carried_over_count', 'sales_amt', 'commission_amt', 'payment_fee_amt',
  'payout_amt', 'status', 'hold_reason', 'scheduled_pay_date', 'held_at', 'paid_at', 'created_at'],
groups.map((g) => [g.id, g.storeId, g.start, `${g.start} + INTERVAL 6 DAY`, g.items.length, 0, g.sum('itemAmt'), g.sum('commission'), g.sum('fee'), g.sum('payout'),
  q(g.status), g.held ? "'스토어 정지 · 폐점 — 자동 보류'" : 'NULL', `${g.start} + INTERVAL 10 DAY`, g.held ? aggregatedAt(g) : 'NULL',
  g.status === 'PAID' ? paidAt(g) : 'NULL', aggregatedAt(g)]))}
${insert('settlement_item', ['settlement_item_id', 'settlement_id', 'order_item_id', 'order_no', 'store_id', 'product_id', 'product_name', 'category_id', 'item_amt',
  'commission_rate', 'commission_amt', 'payment_fee_amt', 'confirmed_at'],
groups.flatMap((g) => g.items.map((o) => [o.id, g.id, o.id, o.orderNo, o.store.storeId, o.product, q(o.p.name), o.topCategory, o.itemAmt, o.rate.toFixed(2),
  o.commission, o.fee, o.t.confirmed])))}
${insert('payout', ['payout_id', 'settlement_id', 'payout_amt', 'bank_name', 'account_number', 'account_holder', 'paid_at'],
  groups.filter((g) => g.status === 'PAID').map((g) => [g.id, g.id, g.sum('payout'), "'곳간은행'", q(`100-0000-${String(g.storeId).padStart(6, '0')}`), q(g.store.owner),
    paidAt(g)]))}
${insert('settlement_history', ['settlement_history_id', 'settlement_id', 'from_status', 'to_status', 'actor_type', 'reason', 'created_at'], settlementHistory)}`;

// ── product
const stockOf = (p, i, n) => Math.floor(p.stock / n) + (i < p.stock % n ? 1 : 0);   // 재고를 조합에 고르게 — 나머지는 앞 조합부터
const inactiveStores = STORES.filter((s) => s.status !== 'ACTIVE').map((s) => s.storeId).join(' · ');
const productSql = `${header('VoC-100', `-- 카테고리 = product.md "시드 카테고리 ID"(10/20 확정) — 상위 ID 1~6 은 settlement 수수료 · store 신청서와 같은 번호,
--   하위 ID = 상위 × 100 + 순번. 시드가 603 까지 넣으므로 관리자가 새로 만드는 카테고리는 604 부터
-- 상품 = 프론트 목업 39개 — product_id = 스토어 × 100 + 순번(order 시드 상품주문과 같은 번호 · 이름 · 판매가), 옵션 = 상품 × 100 + 조합 순번,
--   이미지 = 상품 × 10 + 순번 · S3 키 products/seed/…(프론트 public/images/p 파일을 images 버킷에 올려 둠). 정지 스토어(${inactiveStores}) 상품은 is_store_active FALSE
--   store_status = 운영 중이 아닌 스토어만(행이 없으면 운영 중) — 정지 스토어가 새로 등록하는 상품도 숨김으로 시작`)}
${insert('category', ['category_id', 'parent_id', 'name', 'name_en', 'sort_order', 'is_active'],
  CATEGORY_TREE.map((c) => [c.id, 'NULL', q(c.name), q(c.en), c.sort, 'TRUE']))}
${insert('category', ['category_id', 'parent_id', 'name', 'sort_order', 'is_active'],
  CATEGORY_TREE.flatMap((c) => c.subs.map((s, i) => [c.id * 100 + i + 1, c.id, q(s), i + 1, 'TRUE'])))}
${insert('product', ['product_id', 'category_id', 'store_id', 'name', 'price', 'discount_rate', 'sale_price', 'description', 'material', 'origin',
  'option1_name', 'option2_name', 'status', 'is_store_active', 'store_status_changed_at', 'created_at'],
CATALOG.map((p) => {
  const store = storeById[Math.floor(p.id / 100)];
  const inactive = store.status !== 'ACTIVE';
  return [p.id, subCategoryId(p), store.storeId, q(p.name), p.price, p.discount.toFixed(2), salePrice(p),
    q(`<p>${p.name}</p><p>소재 · 원재료: ${p.material} / 원산지: ${p.origin}</p>`), q(p.material), q(p.origin),
    q(p.options[0]?.[0] ?? null), q(p.options[1]?.[0] ?? null), q(p.status ?? 'ON_SALE'), inactive ? 'FALSE' : 'TRUE',
    inactive ? suspendedAt(store) : 'NULL', ago(p.created)];
}))}
${insert('store_status', ['store_id', 'status', 'status_changed_at'],
  STORES.filter((s) => s.status !== 'ACTIVE').map((s) => [s.storeId, q(s.status), suspendedAt(s)]))}
${insert('product_option', ['product_option_id', 'product_id', 'option1_value', 'option2_value', 'extra_price', 'stock', 'created_at'],
  CATALOG.flatMap((p) => combos(p).map((c, i, all) => [p.id * 100 + i + 1, p.id, q(c[0] ?? null), q(c[1] ?? null), 0, stockOf(p, i, all.length), ago(p.created)])))}
${insert('product_image', ['product_image_id', 'product_id', 's3_key', 'sort_order', 'created_at'],
  CATALOG.flatMap((p) => [1, 2, 3].map((n) => [p.id * 10 + n - 1, p.id, q(`products/seed/${p.mock}-${n}.jpg`), n - 1, ago(p.created)])))}`;

// ── review — 작성 자격 = 구매확정 상품주문 전부(OrderItemConfirmed 로 받는 값 그대로), 리뷰 · 문의 = scenario REVIEWS · INQUIRIES
const orderById = Object.fromEntries(orders.map((o) => [o.id, o]));
const reviews = REVIEWS.map((r) => {
  const o = orderById[r.orderItemId];
  if (o?.t.status !== 'CONFIRMED') throw new Error(`리뷰 ${r.orderItemId}: 구매확정 상품주문이 아님`);
  if (r.content.length < 20 || r.content.length > 2000) throw new Error(`리뷰 ${r.orderItemId}: 본문 20~2,000자`);
  return { ...r, o, at: `${o.t.confirmed} + INTERVAL ${r.afterDays} DAY + INTERVAL 5 HOUR` };
});
const nickById = Object.fromEntries([...LOGIN_BUYERS, ...VIEW_BUYERS].map((b) => [b.userId, b.nick ?? mask(b.name)]));
const reviewSql = `${header('perish95', `-- 작성 자격 = order 시드의 구매확정 상품주문 전부(review_eligibility_id = order_item_id), 값은 OrderItemConfirmed payload 와 같게
--   buyer_display_name = order 시드 orderer_display_name(마스킹 실명), option_name = order_item.option_name, thumbnail_url = order_item.thumbnail_url(시드는 NULL)
-- 리뷰 id = 상품주문 id. buyer1(user 4)은 1001 만 작성 — 1006 · 1007 은 '작성 가능한 리뷰'로 남김. 블라인드 1건(신고 처리 시연) · 판매자 답글 일부
-- 상품 문의 = 스토어 1 · 2 상품 위주(공개 · 비밀글, 답변완료 · 미답변 섞음). 작성자 표시 이름 = 닉네임, thumbnail_url 은 NULL(조회 URL 이 환경마다 달라 비움)
-- 리뷰 사진(review_image)은 S3 reviews/ 객체가 없어 넣지 않는다. 평균 · 리뷰 수는 조회 때 집계(요약 테이블 없음). 이벤트 멱등(processed_event) 행 없음`)}
${insert('review_eligibility', ['review_eligibility_id', 'order_item_id', 'user_id', 'product_id', 'store_id', 'buyer_display_name', 'product_name', 'option_name',
  'thumbnail_url', 'confirmed_at', 'expires_at', 'created_at'],
confirmed.map((o) => [o.id, o.id, o.buyer, o.product, o.store.storeId, q(mask(o.buyerInfo.name)), q(o.p.name), q(o.p.optionName), 'NULL', o.t.confirmed,
  `${o.t.confirmed} + INTERVAL 30 DAY`, o.t.confirmed]))}
${insert('review', ['review_id', 'review_eligibility_id', 'order_item_id', 'user_id', 'product_id', 'store_id', 'writer_display_name', 'rating', 'title', 'content',
  'fit', 'reply_content', 'is_blinded', 'blind_reason', 'replied_at', 'blinded_at', 'created_at'],
reviews.map((r) => [r.o.id, r.o.id, r.o.id, r.o.buyer, r.o.product, r.o.store.storeId, q(mask(r.o.buyerInfo.name)), r.rating, q(r.title ?? null), q(r.content),
  q(r.fit ?? null), q(r.reply ?? null), r.blind ? 'TRUE' : 'FALSE', q(r.blind?.reason ?? null), r.reply ? `${r.at} + INTERVAL 1 DAY` : 'NULL',
  r.blind ? `${r.o.t.confirmed} + INTERVAL ${r.blind.afterDays} DAY` : 'NULL', r.at]))}
${insert('product_inquiry', ['product_inquiry_id', 'user_id', 'product_id', 'store_id', 'product_name', 'thumbnail_url', 'writer_display_name', 'content',
  'answer_content', 'is_secret', 'answered_at', 'created_at'],
INQUIRIES.map((n, i) => [i + 1, n.userId, n.product, Math.floor(n.product / 100), q(catalogById[n.product].name), 'NULL', q(nickById[n.userId]), q(n.content),
  q(n.answer ?? null), n.secret ? 'TRUE' : 'FALSE', n.answer ? ago(n.daysAgo - n.answerAfterDays, '16:00:00') : 'NULL', ago(n.daysAgo, '13:00:00')]))}`;

// ── admin — 신고 · 조치 이력. 조치 1번이 그 대상의 열린 신고를 모두 닫음(RESOLVED). 시각: 신고 < 조치 = review 블라인드 시각 · user 정지 시각
const reviewById = Object.fromEntries(reviews.map((r) => [r.o.id, r]));
const viewById = Object.fromEntries(VIEW_BUYERS.map((b) => [b.userId, b]));
let reportId = 0;
const moderations = MODERATIONS.map((m, i) => {
  let actedAt;
  let summary;
  if (m.targetType === 'REVIEW') {
    const r = reviewById[m.targetId];
    check(r?.blind, `신고 · 조치 리뷰 ${m.targetId}: review 시드의 블라인드 리뷰가 아님`);
    actedAt = `${r.o.t.confirmed} + INTERVAL ${r.blind.afterDays} DAY`;
    summary = r.content.slice(0, 100);
    check(m.reason === r.blind.reason, `리뷰 ${m.targetId}: 조치 사유 ↔ review blind_reason`);
  } else {
    const u = viewById[m.targetId];
    check(u?.suspended, `신고 · 조치 회원 ${m.targetId}: user 시드의 정지 회원이 아님`);
    actedAt = ago(u.suspended.daysAgo);
    summary = u.name;
    check(m.reason === u.suspended.reason && m.suspendDays === u.suspended.days, `회원 ${m.targetId}: 조치 사유 · 기간 ↔ user 정지`);
  }
  // 신고는 조치 하루 전부터 몇 시간 간격으로
  const reports = m.reports.map((rp, k) => ({ ...rp, id: (reportId += 1), at: `${actedAt} - INTERVAL 1 DAY + INTERVAL ${k * 3} HOUR` }));
  return { ...m, id: i + 1, actedAt, summary, reports };
});
const adminSql = `${header('jihoon-devstu', `-- 신고 · 조치 = 블라인드 리뷰(review 시드 1004) · 정지 회원(user 시드 111)의 근거. 조치 1번이 그 대상의 신고를 모두 닫음(RESOLVED)
--   조치 시각 = review blinded_at · user suspended_at, 사유 = 대상 서비스에 넣은 사유와 같음. 처리한 관리자 = user 1. 처리 대기 신고는 없음(시연에서 새로 신고)`)}
${insert('moderation_action', ['moderation_action_id', 'target_type', 'target_id', 'action', 'reason', 'suspend_days', 'admin_id', 'report_count', 'created_at'],
  moderations.map((m) => [m.id, q(m.targetType), m.targetId, q(m.action), q(m.reason), m.suspendDays ?? 'NULL', 1, m.reports.length, m.actedAt]))}
${insert('report', ['report_id', 'target_type', 'target_id', 'reporter_id', 'reporter_role', 'reason_code', 'detail', 'target_summary', 'status', 'moderation_action_id',
  'created_at', 'updated_at'],
  moderations.flatMap((m) => m.reports.map((rp) => [rp.id, q(m.targetType), m.targetId, rp.reporter, q(rp.role), q(rp.code), 'NULL', q(m.summary), "'RESOLVED'", m.id,
    rp.at, m.actedAt])))}`;

for (const [service, sql] of [['user', userSql], ['store', storeSql], ['product', productSql], ['order', orderSql], ['payment', paymentSql],
  ['settlement', settlementSql], ['review', reviewSql], ['admin', adminSql]]) {
  console.log(write(service, sql));
}
console.log(`회원 ${userRows.length + 3} · 스토어 ${STORES.length} · 상품 ${CATALOG.length} · 주문 ${orders.length}(구매확정 ${confirmed.length}) · 정산서 ${groups.length}`
  + ` (${groups.map((g) => `${g.storeId}:W-${g.week}:${g.status}:${g.sum('payout')}`).join(' ')})`
  + ` · 리뷰 자격 ${confirmed.length} · 리뷰 ${reviews.length} · 문의 ${INQUIRIES.length} · 신고 ${reportId} · 조치 ${moderations.length}`);
