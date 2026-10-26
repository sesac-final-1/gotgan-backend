// 시연 시드 생성 — node scripts/seed-gen/gen.mjs → 서비스별 db/seed/R__demo_seed.sql (user · store · order · payment · settlement)
// 직접 고치지 말고 scenario.mjs 를 고친 뒤 다시 생성한다. 담당 구지훈 (각 파일 주인이 리뷰)
// 금액 · 수수료 · 포인트는 서버 규칙 그대로 계산: 판매수수료(상위 카테고리율) · 결제수수료 2.2% 를 상품주문마다 원 단위 내림,
// 적립 = (상품주문 금액 − 사용 포인트) × 5% 내림, 배송비는 스토어 정책(무료 기준 이상이면 0)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  CATEGORY, COMMISSION, EARN_RATE, GRADE, LOGIN_BUYERS, ORDERS, PAYMENT_FEE_RATE, PRODUCTS, REPRESENTATIVE, STORES, VIEW_BUYERS,
} from './scenario.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const q = (v) => (v === null || v === undefined ? 'NULL' : typeof v === 'number' ? String(v) : typeof v === 'boolean' ? String(v).toUpperCase()
  : `'${String(v).replace(/'/g, "''")}'`);
const ago = (days, time = '10:00:00') => `TIMESTAMP(@today - INTERVAL ${days} DAY, '${time}')`;
const json = (items) => `JSON_ARRAY(${items.map((p) => `JSON_OBJECT('name', ${q(p.name)}, 'subCategory', ${q(p.subCategory)}, 'price', ${p.price})`).join(', ')})`;
const mask = (name) => (name.length <= 2 ? `${name[0]}*` : `${name[0]}${'*'.repeat(name.length - 2)}${name.at(-1)}`);
const storeById = Object.fromEntries(STORES.map((s) => [s.storeId, s]));
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
  return { ...o, id, p, store, itemAmt, shippingFee, payAmt: itemAmt + shippingFee, topCategory, t, orderNo,
    earned: Math.floor((itemAmt * EARN_RATE) / 100), buyerInfo: buyerById[o.buyer] };
});

// ── user
const buyerTotals = Object.fromEntries(LOGIN_BUYERS.map((b) => [b.userId, { point: 0, confirmed: 0 }]));
for (const o of orders) {
  buyerTotals[o.buyer].point += o.earned;
  if (o.t.status === 'CONFIRMED') buyerTotals[o.buyer].confirmed += o.itemAmt;
}
const userCols = ['user_id', 'email', 'name', 'nick', 'phone', 'signup_provider', 'point_balance', 'total_confirmed_amt', 'grade', 'status',
  'status_reason', 'suspended_at', 'suspended_until', 'last_login_at', 'created_at'];
const owners = STORES.filter((s) => s.userId >= 200);
const userRows = [
  ...LOGIN_BUYERS.map((b) => [b.userId, q(b.email), q(b.name), q(b.nick), q(b.phone), "'LOCAL'", buyerTotals[b.userId].point, buyerTotals[b.userId].confirmed,
    q(b.grade), "'ACTIVE'", 'NULL', 'NULL', 'NULL', ago(b.login, '09:00:00'), ago(b.joined)]),
  ...VIEW_BUYERS.map((b) => [b.userId, q(b.email), q(b.name), q(b.nick), q(b.phone), "'LOCAL'", b.point, b.spent, q(b.grade),
    q(b.suspended ? 'SUSPENDED' : 'ACTIVE'), q(b.suspended?.reason ?? null), b.suspended ? ago(b.suspended.daysAgo) : 'NULL',
    b.suspended ? ago(b.suspended.daysAgo - b.suspended.days) : 'NULL', ago(b.login, '09:00:00'), ago(b.joined)]),
  ...owners.map((s) => [s.userId, q(`seller${s.storeId}@seed.gotgan.example`), q(s.owner), 'NULL', q(s.phone), "'LOCAL'", 0, 0, "'NORMAL'", "'ACTIVE'",
    'NULL', 'NULL', 'NULL', ago(1, '09:00:00'), ago(s.since + 3)]),
];
const pointRows = [
  ...orders.map((o) => [o.id, o.buyer, o.id, o.orderNo, "'EARN'", o.earned, 'NULL',
    `${orders.filter((x) => x.buyer === o.buyer && x.id <= o.id).reduce((a, x) => a + x.earned, 0)}`, q(`seed-earn-${o.id}`), o.t.paid]),
  ...VIEW_BUYERS.filter((b) => b.point > 0).map((b) => [b.userId * 10, b.userId, 'NULL', 'NULL', "'EARN'", b.point, 'NULL', b.point, q(`seed-base-${b.userId}`),
    ago(Math.min(b.joined, 30))]),
];
const userSql = `${header('perish95', `-- 1 관리자 · 2 · 3 판매자 · 4~6 구매자 = Keycloak 시드 계정(이메일 · user_id 고정 — gotgan-config scripts/keycloak-seed.sh). keycloak_id 는 NULL(이메일로 연결)
-- 4~6 은 목업 u1~u3 프로필, 포인트 · 누적 구매확정 금액은 주문 시드에서 계산(잔액 = 적립 이력 합)
-- 104~114 목업 구매자 · 201~ 스토어 대표자 = 조회용(Keycloak 계정 없음 — 로그인 불가). 111 은 이용정지(관리자 회원 화면 시연)`)}
-- 시연 계정 — 프로필은 시드 값으로 되돌리고 포인트 · 상태는 시연 중 바뀐 값 유지
INSERT INTO users (user_id, email, name, nick, phone, signup_provider, grade, status)
VALUES
  (1, 'wlsgksvheh+admin@gmail.com',   '곳간 관리자', '관리자', '010-0000-0001', 'LOCAL', 'NORMAL', 'ACTIVE'),
  (2, 'wlsgksvheh+seller1@gmail.com', ${q(storeById[1].owner)}, NULL, ${q(storeById[1].phone)}, 'LOCAL', 'NORMAL', 'ACTIVE'),
  (3, 'wlsgksvheh+seller2@gmail.com', ${q(storeById[2].owner)}, NULL, ${q(storeById[2].phone)}, 'LOCAL', 'NORMAL', 'ACTIVE')
AS seed
ON DUPLICATE KEY UPDATE email = seed.email, name = seed.name, nick = seed.nick, phone = seed.phone;

${insert('users', userCols, userRows)}
${insert('point_history', ['point_history_id', 'user_id', 'order_item_id', 'order_no', 'type', 'amount', 'shortfall_amt', 'balance_after', 'ref_key', 'created_at'], pointRows)}`;

// ── store
const storeSql = `${header('VoC-100', `-- 목업 스토어 9개(프론트 mocks/fixtures/catalog.js) — 모두 승인된 입점 신청서에서 생긴 상태로 신청서 · 이력도 함께
--   store_id = 10 + 목업 번호. 단 시연 판매자 로그인 스토어 1 · 2 는 Keycloak 시드 · settlement 시드와 번호가 같아야 해서
--   seller1 user 2 → store 1 (산골농부 박씨네, 식품) · seller2 user 3 → store 2 (토담공방, 리빙). 나머지 대표자는 user 200 + 목업 번호(조회용)
-- category_id = productdb 카테고리 시드(1~6). 신청서 id = 스토어 id, 이력 id = 스토어 id × 10 + 순번. 볕드는집(15)은 정지
-- 사업자 · 계좌는 가짜 값. 서류(application_document)는 S3 객체가 없어 넣지 않는다. 이벤트는 발행되지 않는다`)}
${insert('store_application', ['store_application_id', 'user_id', 'category_id', 'biz_type', 'company_name', 'owner_name', 'biz_reg_no', 'ecommerce_reg_no',
  'manager_phone', 'manager_email', 'business_address', 'store_name', 'slug', 'intro', 'representative_products', 'expected_monthly_sales', 'sales_channel',
  'bank_name', 'account_no', 'account_holder', 'status', 'marketing_agreed', 'terms_agreed_at', 'status_changed_at', 'created_at'],
STORES.map((s) => [s.storeId, s.userId, CATEGORY[s.cat], "'INDIVIDUAL'", q(s.name), q(s.owner), q(s.bizNo), q(`2026-시연-${String(s.storeId).padStart(4, '0')}`),
  q(s.phone), q(s.email), q(`서울 성동구 성수이로 ${s.storeId}`), q(s.name), q(s.slug), q(`<p>${s.intro}</p>`), json(REPRESENTATIVE[s.cat]), "'FROM_5M_TO_20M'", 'NULL',
  "'곳간은행'", q(`100-0000-${String(s.storeId).padStart(6, '0')}`), q(s.owner), "'APPROVED'", 'FALSE', ago(s.since + 3), ago(s.since), ago(s.since + 3)]))}
${insert('application_history', ['application_history_id', 'store_application_id', 'from_status', 'to_status', 'actor_type', 'reason', 'created_at'],
  STORES.flatMap((s) => [[s.storeId * 10 + 1, s.storeId, 'NULL', "'PENDING'", "'APPLICANT'", 'NULL', ago(s.since + 3)],
    [s.storeId * 10 + 2, s.storeId, "'PENDING'", "'APPROVED'", "'ADMIN'", 'NULL', ago(s.since)]]))}
${insert('store', ['store_id', 'store_application_id', 'user_id', 'store_name', 'slug', 'intro', 'color', 'cs_phone', 'shipping_fee', 'free_shipping_threshold',
  'courier', 'dispatch_note', 'grade', 'status', 'status_reason', 'created_at'],
STORES.map((s) => [s.storeId, s.storeId, s.userId, q(s.name), q(s.slug), q(`<p>${s.intro}</p>`), q(s.color), q(s.phone), s.shipFee, q(s.freeOver),
  q(s.courier), q(s.dispatch), q(GRADE[s.grade]), q(s.status), q(s.statusReason ?? null), ago(s.since)]))}
${insert('store_status_history', ['store_status_history_id', 'store_id', 'from_status', 'to_status', 'actor_type', 'reason', 'created_at'],
  STORES.flatMap((s) => [[s.storeId * 10 + 1, s.storeId, 'NULL', "'ACTIVE'", "'ADMIN'", 'NULL', ago(s.since)],
    ...(s.status === 'SUSPENDED' ? [[s.storeId * 10 + 2, s.storeId, "'ACTIVE'", "'SUSPENDED'", "'ADMIN'", q(s.statusReason), ago(s.suspendedDaysAgo)]] : [])]))}`;

// ── order
const flow = ['PAYMENT_DONE', 'PREPARING', 'SHIPPING', 'DELIVERED', 'CONFIRMED'];
const historyRows = orders.flatMap((o) => {
  const upto = flow.indexOf(o.t.status);
  const rows = [[o.id * 10 + 1, o.id, o.buyer, 'NULL', "'ORDERED'", "'BUYER'", 'NULL', o.t.paid],
    [o.id * 10 + 2, o.id, 'NULL', "'ORDERED'", "'PAYMENT_DONE'", "'SYSTEM'", 'NULL', o.t.paid]];
  const at = { PREPARING: `${o.t.paid} + INTERVAL 3 HOUR`, SHIPPING: o.t.shipped, DELIVERED: o.t.delivered, CONFIRMED: o.t.confirmed };
  const actor = { PREPARING: ['SELLER', o.store.userId], SHIPPING: ['SELLER', o.store.userId], DELIVERED: ['SYSTEM', null], CONFIRMED: ['BUYER', o.buyer] };
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
  q(o.p.name), 'NULL', o.p.price, o.qty, o.itemAmt, 0, o.earned, o.t.shipped ? q(o.store.courier) : 'NULL',
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

${insert('settlement_store', ['settlement_store_id', 'store_id', 'store_name', 'seller_email', 'bank_name', 'account_number', 'account_holder', 'store_status'],
  STORES.map((s) => [s.storeId, s.storeId, q(s.name), q(s.email), "'곳간은행'", q(`100-0000-${String(s.storeId).padStart(6, '0')}`), q(s.owner), q(s.status)]))}
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

for (const [service, sql] of [['user', userSql], ['store', storeSql], ['order', orderSql], ['payment', paymentSql], ['settlement', settlementSql]]) {
  console.log(write(service, sql));
}
console.log(`회원 ${userRows.length + 3} · 스토어 ${STORES.length} · 주문 ${orders.length}(구매확정 ${confirmed.length}) · 정산서 ${groups.length}`
  + ` (${groups.map((g) => `${g.storeId}:W-${g.week}:${g.status}:${g.sum('payout')}`).join(' ')})`);
