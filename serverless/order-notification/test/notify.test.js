// 핸들러 단위 테스트 — node --test (담당 jihoon-devstu). AWS 클라이언트는 가짜로 넣는다
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TEMPLATES, createHandler, displayDateTime } from '../src/notify.js';

const ENV = { TABLE: 'sesac-gojigi-notifications', FROM_EMAIL: 'from@example.com', FRONT_URL: 'https://gotgan-ashy.vercel.app' };
const FRONT = ENV.FRONT_URL;
const NOW = new Date('2026-10-21T03:00:00Z');

const envelope = (eventType, payload, eventId = 'e-1') =>
  ({ eventId, eventType, occurredAt: '2026-10-21T12:00:00+09:00', aggregateId: 'x', version: 1, payload });
const record = (body, messageId = 'm-1') => ({ messageId, body: JSON.stringify(body) });

/** 보낸 명령을 기록하는 가짜 클라이언트 — failOn: 그 명령 이름에서 예외 */
function fake(failOn = {}) {
  const calls = [];
  const client = (name) => ({
    send: async (cmd) => {
      const type = cmd.constructor.name;
      calls.push({ client: name, type, input: cmd.input });
      if (failOn[type]) throw failOn[type];
    },
  });
  const quiet = { info() {}, warn() {}, error() {} };
  return { calls, deps: { ddb: client('ddb'), ses: client('ses'), env: ENV, log: quiet, now: () => NOW } };
}

// 10/21 필드 추가 요청분까지 담긴 payload
const orderPaid = {
  orderNo: '20261021-000123', buyerId: 4, ordererEmail: 'buyer@example.com', ordererName: '김구매', payAmt: 64400, usedPoint: 2000,
  items: [
    { orderItemId: 1, earnedPoint: 1500, productName: '제철 사과 3kg', optionName: '특', quantity: 1, itemAmt: 32000 },
    { orderItemId: 2, earnedPoint: 1620, productName: '햇배 5kg', quantity: 2, itemAmt: 32400 },
  ],
};

// ── 처리 흐름

test('정상 — 선점(TTL 90일) → 메일 → 발송 완료, 키는 USER#{buyerId} · {occurredAt}#{eventId}', async () => {
  const { calls, deps } = fake();
  const result = await createHandler(deps)({ Records: [record(envelope('OrderPaid', orderPaid))] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.deepEqual(calls.map((c) => c.type), ['PutCommand', 'SendEmailCommand', 'UpdateCommand']);
  const [claim, mail, done] = calls;
  assert.equal(claim.input.Item.PK, 'USER#4');
  assert.equal(claim.input.Item.SK, '2026-10-21T12:00:00+09:00#e-1');
  assert.equal(claim.input.Item.ttl, Math.floor(NOW.getTime() / 1000) + 90 * 24 * 3600);
  assert.match(claim.input.ConditionExpression, /attribute_not_exists\(SK\) OR #s <> :sent/);
  assert.deepEqual(mail.input.Destination.ToAddresses, ['buyer@example.com']);
  assert.equal(done.input.ExpressionAttributeValues[':sent'], 'SENT');
});

test('이미 보낸 알림(조건 실패)은 건너뛴다 — 재전달에도 메일 1번', async () => {
  const err = Object.assign(new Error('conditional'), { name: 'ConditionalCheckFailedException' });
  const { calls, deps } = fake({ PutCommand: err });
  const result = await createHandler(deps)({ Records: [record(envelope('OrderPaid', orderPaid))] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.deepEqual(calls.map((c) => c.type), ['PutCommand']);
});

test('메일 실패 — 그 메시지만 batchItemFailures, 발송 완료를 남기지 않음', async () => {
  const { calls, deps } = fake({ SendEmailCommand: new Error('SES 스로틀') });
  const result = await createHandler(deps)({ Records: [record(envelope('OrderPaid', orderPaid), 'm-bad')] });

  assert.deepEqual(result, { batchItemFailures: [{ itemIdentifier: 'm-bad' }] });
  assert.ok(!calls.some((c) => c.type === 'UpdateCommand'));
});

test('받는 주소가 없으면 실패로 돌려준다(재전달 → DLQ 알람으로 계약 누락을 알게 됨) · 깨진 JSON 도 실패', async () => {
  const { calls, deps } = fake();
  const noEmail = { ...orderPaid, ordererEmail: undefined };
  const result = await createHandler(deps)({ Records: [record(envelope('OrderPaid', noEmail), 'm-noemail'),
    { messageId: 'm-broken', body: '{not json' }] });

  assert.deepEqual(result, { batchItemFailures: [{ itemIdentifier: 'm-noemail' }, { itemIdentifier: 'm-broken' }] });
  assert.equal(calls.length, 0);
});

test('알림 대상이 아닌 이벤트(OrderItemConfirmed 등)는 무시 — 큐를 막지 않게', async () => {
  const { calls, deps } = fake();
  const result = await createHandler(deps)({ Records: [record(envelope('OrderItemConfirmed', {}))] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.equal(calls.length, 0);
});

// ── 메일 내용 (NTF-01~04)

test('NTF-01 주문 확인 — 상품 · 수량 · 금액, 결제 금액 · 사용 · 적립 예정 포인트(구매확정 시), 주문 상세 링크', () => {
  const { subject, text } = TEMPLATES.OrderPaid.mail(orderPaid, FRONT);

  assert.equal(subject, '[곳간] 주문이 완료되었습니다 — 20261021-000123');
  assert.match(text, /^김구매님, 주문이 완료되었습니다\./);
  assert.match(text, /· 제철 사과 3kg \(특\) × 1 — 32,000원/);
  assert.match(text, /· 햇배 5kg × 2 — 32,400원/);
  assert.match(text, /· 결제 금액 64,400원/);
  assert.match(text, /· 사용 포인트 2,000P/);
  assert.match(text, /· 적립 예정 포인트 3,120P \(구매확정 시 적립\)/);
  assert.match(text, /https:\/\/gotgan-ashy\.vercel\.app\/mypage\/orders\/20261021-000123/);
});

test('NTF-01 필드 추가 전(지금 계약) — 이름 · 상품명 · 사용 포인트가 없어도 보낸다', () => {
  const current = { orderNo: '20261021-000124', buyerId: 4, ordererEmail: 'b@example.com', payAmt: 10000,
    items: [{ orderItemId: 1, earnedPoint: 500 }] };
  const { text } = TEMPLATES.OrderPaid.mail(current, FRONT);

  assert.match(text, /^고객님,/);
  assert.match(text, /· 상품 1건/);
  assert.doesNotMatch(text, /사용 포인트/);
  assert.match(text, /· 적립 예정 포인트 500P/);
});

test('NTF-02 배송 시작 — 상품명 · 택배사 · 송장번호, buyerId 없으면 ORDER# 키', () => {
  const p = { orderItemId: 1, orderNo: '20261021-000123', ordererEmail: 'b@example.com', ordererName: '김구매',
    productName: '햇배 5kg', courier: 'CJ대한통운', trackingNo: '123456789012' };
  const { subject, text } = TEMPLATES.OrderItemShipped.mail(p, FRONT);

  assert.equal(subject, '[곳간] 상품이 출발했습니다 — 햇배 5kg');
  assert.match(text, /· 택배사 CJ대한통운/);
  assert.match(text, /· 송장번호 123456789012/);
  assert.equal(TEMPLATES.OrderItemShipped.owner(p), 'ORDER#20261021-000123');
  assert.equal(TEMPLATES.OrderItemShipped.owner({ ...p, buyerId: 4 }), 'USER#4');
});

test('NTF-03 취소 · 반품 완료 — 환불 금액 · 돌려드린 포인트 · 회수된 적립 포인트', () => {
  const p = { orderItemId: 1, orderNo: '20261021-000123', ordererEmail: 'b@example.com', productName: '제철 사과 3kg',
    refundAmt: 30000, restoredPoint: 2000, revokedPoint: 1500 };
  const cancelled = TEMPLATES.OrderItemCancelled.mail(p, FRONT);
  const returned = TEMPLATES.OrderItemReturned.mail({ ...p, restoredPoint: 0, revokedPoint: 0 }, FRONT);

  assert.equal(cancelled.subject, '[곳간] 취소가 완료되었습니다 — 제철 사과 3kg');
  assert.match(cancelled.text, /· 환불 금액 30,000원/);
  assert.match(cancelled.text, /· 돌려드린 포인트 2,000P/);
  assert.match(cancelled.text, /· 회수된 적립 포인트 1,500P/);
  assert.equal(returned.subject, '[곳간] 반품이 완료되었습니다 — 제철 사과 3kg');
  assert.match(returned.text, /요청하신 반품이 완료되었습니다/);
  assert.doesNotMatch(returned.text, /포인트/);
});

test('NTF-04 입점 승인 · 반려(재신청 가능일) · 보완요청(서류 · 기한) — userId 없으면 APPLICATION# 키', () => {
  const approved = TEMPLATES.StoreApproved.mail({ storeId: 3, userId: 2, storeName: '곳간상회', sellerEmail: 's@example.com' }, FRONT);
  assert.equal(approved.subject, '[곳간] 입점이 승인되었습니다 — 곳간상회');
  assert.match(approved.text, /\/seller$/m);

  const rejectedPayload = { applicationId: 11, storeName: '곳간상회', sellerEmail: 's@example.com', reason: '사업자등록증 불일치',
    decidedAt: '2026-10-21T10:00:00+09:00' };
  const rejected = TEMPLATES.StoreApplicationRejected.mail(rejectedPayload, FRONT);
  assert.match(rejected.text, /· 사유 사업자등록증 불일치/);
  assert.match(rejected.text, /2026-11-20 이후 다시 신청할 수 있습니다/);
  assert.equal(TEMPLATES.StoreApplicationRejected.owner(rejectedPayload), 'APPLICATION#11');
  assert.equal(TEMPLATES.StoreApplicationRejected.owner({ ...rejectedPayload, userId: 5 }), 'USER#5');

  const supplement = TEMPLATES.StoreApplicationSupplementRequested.mail({ applicationId: 11, storeName: '곳간상회',
    sellerEmail: 's@example.com', requestedDocs: ['통신판매업 신고증'], message: '사본이 흐립니다', dueAt: '2026-10-28T23:59:00+09:00' }, FRONT);
  assert.match(supplement.text, /- 통신판매업 신고증/);
  assert.match(supplement.text, /2026-10-28 23:59까지 보완하지 않으면 신청이 자동 반려됩니다/);
});

test('일시 표기 — KST ISO 를 날짜 · 시:분으로', () => {
  assert.equal(displayDateTime('2026-10-28T23:59:00+09:00'), '2026-10-28 23:59');
  assert.equal(displayDateTime(null), '');
});
