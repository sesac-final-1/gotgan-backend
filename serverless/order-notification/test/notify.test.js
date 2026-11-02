// 핸들러 단위 테스트 — node --test (담당 jihoon-devstu). AWS 클라이언트는 가짜로 넣는다
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TEMPLATES, createHandler, displayDateTime, esc } from '../src/notify.js';

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

test('SES 가 받는 주소를 거절(MessageRejected — 샌드박스 미인증 주소)하면 재전달하지 않고 REJECTED 로 남긴다', async () => {
  const err = Object.assign(new Error('Email address is not verified.'), { name: 'MessageRejected' });
  const { calls, deps } = fake({ SendEmailCommand: err });
  const result = await createHandler(deps)({ Records: [record(envelope('OrderPaid', orderPaid), 'm-rejected')] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.deepEqual(calls.map((c) => c.type), ['PutCommand', 'SendEmailCommand', 'UpdateCommand']);
  assert.equal(calls[2].input.ExpressionAttributeValues[':rejected'], 'REJECTED');
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

// 11/02 이경석 요청 — order DeliveryTxService.deliver · 자동 배송완료 · 교환 재배송 배송완료
const delivered = {
  orderItemId: 1, orderNo: '20261102-000001', buyerId: 4, ordererEmail: 'buyer@example.com', ordererName: '김구매',
  productName: '제철 사과 3kg', optionName: '특', courier: 'CJ대한통운', trackingNo: '123456789012',
  deliveredAt: '2026-11-02T15:00:00+09:00', autoConfirmAt: '2026-11-09T15:00:00+09:00',
};

test('DLV-04 배송 완료 — 상품 · 택배 · 배송완료 시각 · 자동 구매확정 예정일 · 반품 7일 · 주문 상세 링크', () => {
  const { subject, text, html } = TEMPLATES.OrderItemDelivered.mail(delivered, FRONT);

  assert.equal(subject, '[곳간] 배송이 완료되었습니다 — 제철 사과 3kg');
  assert.ok(text.startsWith('김구매님, 주문하신 상품이 도착했습니다.'));
  assert.ok(text.includes('· 상품 제철 사과 3kg (특)'));
  assert.match(text, /· 택배 CJ대한통운 · 123456789012/);
  assert.match(text, /· 배송완료 2026-11-02 15:00/);
  assert.match(text, /2026-11-09 15:00에 자동으로 구매확정됩니다/);
  assert.match(text, /반품 · 교환은 배송완료 후 7일 안에/);
  assert.ok(text.includes('주문 상세: https://gotgan-ashy.vercel.app/mypage/orders/20261102-000001'));
  // HTML — 같은 내용 + 버튼 링크
  assert.match(html, /^<!doctype html>/);
  assert.match(html, /배송이 완료되었습니다/);
  assert.match(html, /김구매님, 주문하신 상품이 도착했어요/);
  assert.ok(html.includes('제철 사과 3kg (특)'));
  assert.match(html, /2026-11-09 15:00에 자동으로 구매확정됩니다/);
  assert.ok(html.includes('href="https://gotgan-ashy.vercel.app/mypage/orders/20261102-000001"'));
  assert.equal(TEMPLATES.OrderItemDelivered.owner(delivered), 'USER#4');
  assert.equal(TEMPLATES.OrderItemDelivered.to(delivered), 'buyer@example.com');
});

test('DLV-04 배송 완료 — 옵션 · 상품명 · 예정일이 없어도 보내고, HTML 에는 글자를 이스케이프해서 넣는다', () => {
  const p = { ...delivered, optionName: null, autoConfirmAt: undefined, ordererName: '<b>김</b>', productName: undefined, buyerId: undefined };
  const { subject, text, html } = TEMPLATES.OrderItemDelivered.mail(p, FRONT);

  assert.equal(subject, '[곳간] 주문하신 상품의 배송이 완료되었습니다 — 20261102-000001');
  assert.doesNotMatch(text, /· 상품/);
  assert.match(text, /배송완료 7일 뒤 자동으로 구매확정됩니다/);
  assert.ok(html.includes('&lt;b&gt;김&lt;/b&gt;님'));
  assert.ok(!html.includes('<b>김</b>'));
  assert.equal(TEMPLATES.OrderItemDelivered.owner(p), 'ORDER#20261102-000001');

  const named = TEMPLATES.OrderItemDelivered.mail({ ...delivered, productName: '사과 <script>', optionName: null }, FRONT);
  assert.match(named.html, /사과 &lt;script&gt;/);
  assert.equal(esc(`"a" & 'b'`), '&quot;a&quot; &amp; &#39;b&#39;');
});

test('SES 본문은 HTML + 텍스트 둘 다 — 메일 앱이 HTML 을 고르고, 못 열면 텍스트', async () => {
  const { calls, deps } = fake();
  await createHandler(deps)({ Records: [record(envelope('OrderItemDelivered', delivered), 'm-1'), record(envelope('OrderPaid', orderPaid, 'e-2'), 'm-2')] });

  const mails = calls.filter((c) => c.type === 'SendEmailCommand').map((c) => c.input.Content.Simple.Body);
  assert.equal(mails.length, 2);
  for (const body of mails) {
    assert.ok(body.Html.Data.startsWith('<!doctype html>'));
    assert.equal(body.Html.Charset, 'UTF-8');
    assert.equal(body.Text.Charset, 'UTF-8');
  }
  assert.ok(mails[0].Text.Data.includes('주문하신 상품이 도착했습니다'));
  assert.ok(mails[1].Text.Data.includes('주문이 완료되었습니다'));
});

// 11/02 — 메일 7종 모두 같은 HTML 틀(곳간 로고 · 배지 · 제목 · 버튼 · 발신 전용 안내)
const allMails = () => {
  const store = { userId: 9, applicationId: 3, storeName: '오롯이 & 리넨', sellerEmail: 's@example.com' };
  return {
    OrderPaid: [orderPaid, '주문이 완료되었습니다', '/mypage/orders/20261021-000123', '주문완료'],
    OrderItemShipped: [{ ...delivered, deliveredAt: undefined, autoConfirmAt: undefined }, '상품이 출발했습니다', '/mypage/orders/20261102-000001', '배송시작'],
    OrderItemDelivered: [delivered, '배송이 완료되었습니다', '/mypage/orders/20261102-000001', '배송완료'],
    OrderItemCancelled: [{ ...delivered, refundAmt: 32000, restoredPoint: 500 }, '취소가 완료되었습니다', '/mypage/orders/20261102-000001', '취소완료'],
    OrderItemReturned: [{ ...delivered, refundAmt: 32000, revokedPoint: 300 }, '반품이 완료되었습니다', '/mypage/orders/20261102-000001', '반품완료'],
    StoreApproved: [store, '입점이 승인되었습니다', '/seller"', '입점승인'],
    StoreApplicationRejected: [{ ...store, reason: '서류 <불일치>', decidedAt: '2026-10-21T12:00:00+09:00' }, '입점 신청 결과 안내', '/seller/apply"', '반려'],
    StoreApplicationSupplementRequested: [{ ...store, requestedDocs: ['통신판매업 신고증'], message: '상호 확인', dueAt: '2026-10-28T12:00:00+09:00' }, '입점 서류 보완을 요청드립니다', '/seller/apply"', '보완요청'],
  };
};

test('메일 7종 — 모두 HTML 이 있고 같은 틀(로고 · 배지 · 제목 · 버튼 링크 · 발신 전용), 텍스트 본문은 그대로', () => {
  for (const [type, [p, title, link, badge]] of Object.entries(allMails())) {
    const { subject, text, html } = TEMPLATES[type].mail(p, FRONT);
    assert.ok(subject.startsWith('[곳간] '), type);
    assert.ok(text.length > 0, type);
    assert.ok(html.startsWith('<!doctype html>'), type);
    assert.ok(html.includes('>곳간</span>'), `${type} 로고`);
    assert.ok(html.includes(`● ${badge}</span>`), `${type} 배지`);
    assert.ok(html.includes(`>${title}</h1>`), `${type} 제목`);
    assert.ok(html.includes(`href="${FRONT}${link}`), `${type} 버튼 링크`);
    assert.ok(html.includes('이 메일은 발신 전용입니다.'), `${type} 하단`);
  }
});

test('HTML 은 판매자 · 관리자가 쓴 글자(스토어명 · 반려 사유)도 이스케이프 · 판매자 메일은 판매자용 문의 안내', () => {
  const [p] = allMails().StoreApplicationRejected;
  const { html, text } = TEMPLATES.StoreApplicationRejected.mail(p, FRONT);

  assert.ok(html.includes('오롯이 &amp; 리넨 입점 신청을 승인하지 못했습니다.'));
  assert.ok(html.includes('서류 &lt;불일치&gt;'));
  assert.ok(!html.includes('<불일치>'));
  assert.ok(text.includes('· 사유 서류 <불일치>')); // 텍스트 본문은 글자 그대로
  assert.ok(html.includes('판매자 센터 채팅'));
  assert.ok(html.includes('2026-11-20')); // 재신청 가능일 칸
  assert.ok(TEMPLATES.OrderPaid.mail(orderPaid, FRONT).html.includes('마이페이지의 1:1 채팅'));
});

test('금액 강조 — 주문은 결제 금액, 취소 · 반품은 환불 금액 줄을 크게', () => {
  const paid = TEMPLATES.OrderPaid.mail(orderPaid, FRONT).html;
  assert.match(paid, /font-size:17px;color:#ff5a1f;font-weight:800;">64,400원</);
  const cancelled = TEMPLATES.OrderItemCancelled.mail({ ...delivered, refundAmt: 32000 }, FRONT).html;
  assert.match(cancelled, /font-size:17px;color:#ff5a1f;font-weight:800;">32,000원</);
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
