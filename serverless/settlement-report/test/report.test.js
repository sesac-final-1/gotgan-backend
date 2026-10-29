// 핸들러 단위 테스트 — node --test (담당 jihoon-devstu). AWS 클라이언트는 가짜로 넣는다
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildCsv, buildEmail, createHandler, csvCell, displayDateTime, reportKey } from '../src/report.js';

const ENV = { TABLE: 'sesac-gojigi-settlement-reports', BUCKET: 'sesac-gojigi-reports-test', FROM_EMAIL: 'from@example.com',
  FRONT_URL: 'https://gotgan-ashy.vercel.app' };

const payload = {
  settlementId: 42, storeId: 3, week: '2026-W44', payoutAmt: 51212, sellerEmail: 'seller@example.com',
  storeName: '곳간상회', weekStart: '2026-10-26', weekEnd: '2026-11-01', paidAt: '2026-11-05T10:00:26.739816283+09:00',
  salesAmt: 59000, commissionAmt: 6490, paymentFeeAmt: 1298,
  items: [{ orderNo: '20261027-000001', productName: '제철 사과, 5kg "특"', confirmedAt: '2026-10-28T12:00:00+09:00',
    itemAmt: 59000, commissionRate: 11.0, commissionAmt: 6490, paymentFeeAmt: 1298, payoutAmt: 51212 }],
};

const record = (body, messageId = 'm-1') => ({ messageId, body: JSON.stringify(body) });
const envelope = (p = payload, eventId = 'e-1') => ({ eventId, eventType: 'SettlementPaid', aggregateId: '42', version: 1, payload: p });

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
  return { calls, deps: { ddb: client('ddb'), s3: client('s3'), ses: client('ses'), env: ENV, log: quiet } };
}

test('정상 — 선점 → CSV 업로드 → 메일 → 발송 완료 순서, 실패 없음', async () => {
  const { calls, deps } = fake();
  const result = await createHandler(deps)({ Records: [record(envelope())] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.deepEqual(calls.map((c) => c.type), ['PutCommand', 'PutObjectCommand', 'SendEmailCommand', 'UpdateCommand']);
  const [claim, upload, mail, done] = calls;
  assert.equal(claim.input.Item.PK, 'STORE#3');
  assert.equal(claim.input.Item.SK, 'WEEK#2026-W44');
  assert.match(claim.input.ConditionExpression, /attribute_not_exists\(SK\) OR #s <> :sent/);
  assert.equal(upload.input.Key, 'reports/3/2026-W44.csv');
  assert.deepEqual(mail.input.Destination.ToAddresses, ['seller@example.com']);
  assert.equal(done.input.ExpressionAttributeValues[':sent'], 'SENT');
});

test('이미 발송한 정산(조건 실패)은 건너뛴다 — 재전달 · 아웃박스 재발행에도 메일 1번', async () => {
  const err = Object.assign(new Error('conditional'), { name: 'ConditionalCheckFailedException' });
  const { calls, deps } = fake({ PutCommand: err });
  const result = await createHandler(deps)({ Records: [record(envelope())] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.deepEqual(calls.map((c) => c.type), ['PutCommand']);
});

test('메일 실패 — 그 메시지만 batchItemFailures, 발송 완료를 기록하지 않아 재전달 때 다시 처리', async () => {
  const { calls, deps } = fake({ SendEmailCommand: new Error('SES 스로틀') });
  const result = await createHandler(deps)({ Records: [record(envelope(), 'm-bad')] });

  assert.deepEqual(result, { batchItemFailures: [{ itemIdentifier: 'm-bad' }] });
  assert.ok(!calls.some((c) => c.type === 'UpdateCommand'));
});

test('SES 가 받는 주소를 거절(MessageRejected — 샌드박스 미인증 등)하면 REJECTED 로 남기고 재전달하지 않는다', async () => {
  const err = Object.assign(new Error('Email address is not verified.'), { name: 'MessageRejected' });
  const { calls, deps } = fake({ SendEmailCommand: err });
  const result = await createHandler(deps)({ Records: [record(envelope())] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.deepEqual(calls.map((c) => c.type), ['PutCommand', 'PutObjectCommand', 'SendEmailCommand', 'UpdateCommand']);
  assert.equal(calls.at(-1).input.ExpressionAttributeValues[':rejected'], 'REJECTED');
});

test('받는 주소가 없으면 메일을 보내지 않고 실패 — 재전달 → DLQ 알람으로 계약 누락을 알게 됨', async () => {
  const { calls, deps } = fake();
  const result = await createHandler(deps)({ Records: [record(envelope({ ...payload, sellerEmail: null }), 'm-no-email')] });

  assert.deepEqual(result, { batchItemFailures: [{ itemIdentifier: 'm-no-email' }] });
  assert.ok(!calls.some((c) => c.type === 'SendEmailCommand'));
});

test('여러 레코드 중 실패한 것만 돌려준다 — 깨진 JSON · S3 실패', async () => {
  const { deps } = fake();
  const okThenS3Fail = fake({ PutObjectCommand: new Error('S3 503') });
  const ok = await createHandler(deps)({ Records: [record(envelope(), 'm-ok'), { messageId: 'm-broken', body: '{not json' }] });
  assert.deepEqual(ok, { batchItemFailures: [{ itemIdentifier: 'm-broken' }] });

  const s3Fail = await createHandler(okThenS3Fail.deps)({ Records: [record(envelope(), 'm-s3')] });
  assert.deepEqual(s3Fail, { batchItemFailures: [{ itemIdentifier: 'm-s3' }] });
});

test('SettlementPaid 가 아닌 이벤트는 삭제(무시) — 구독 필터 밖 메시지가 큐를 막지 않게', async () => {
  const { calls, deps } = fake();
  const result = await createHandler(deps)({ Records: [record({ eventId: 'x', eventType: 'OrderPaid', payload: {} })] });

  assert.deepEqual(result, { batchItemFailures: [] });
  assert.equal(calls.length, 0);
});

test('빈 이벤트도 처리한다', async () => {
  const { deps } = fake();
  assert.deepEqual(await createHandler(deps)({ Records: [] }), { batchItemFailures: [] });
});

test('CSV — BOM · 머리 정보 · 항목 · 합계, 쉼표 · 따옴표 있는 상품명은 따옴표로 감쌈', () => {
  const csv = buildCsv(payload);

  assert.ok(csv.startsWith('﻿'));
  assert.match(csv, /스토어,곳간상회/);
  assert.match(csv, /정산 주차,2026-W44 \(2026-10-26 ~ 2026-11-01\)/);
  assert.match(csv, /주문번호,상품명,구매확정일시,판매금액,수수료율\(%\),판매수수료,결제수수료,지급액/);
  assert.match(csv, /20261027-000001,"제철 사과, 5kg ""특""",2026-10-28 12:00,59000,11,6490,1298,51212/);
  assert.match(csv, /지급일시,2026-11-05 10:00/);
  assert.match(csv, /합계,1건,,59000,,6490,1298,51212/);
  assert.equal(csvCell(null), '');
  assert.equal(csvCell('줄\n바꿈'), '"줄\n바꿈"');
});

test('엑셀용 일시 — KST ISO(나노초 포함)를 날짜 · 시:분으로, 형식이 다르면 그대로', () => {
  assert.equal(displayDateTime('2026-10-21T12:03:26.739816283+09:00'), '2026-10-21 12:03');
  assert.equal(displayDateTime('2026-10-13T10:00:00+09:00'), '2026-10-13 10:00');
  assert.equal(displayDateTime('2026-10-13'), '2026-10-13');
  assert.equal(displayDateTime(null), '');
});

test('메일 — 제목에 주차 · 지급액, 본문에 판매자 정산 화면 링크(다운로드는 화면에서 10분 링크)', () => {
  const { subject, text } = buildEmail(payload, ENV.FRONT_URL);

  assert.equal(subject, '[곳간] 2026-W44 정산금 51,212원 지급 완료');
  assert.match(text, /https:\/\/gotgan-ashy\.vercel\.app\/seller\/settlements/);
  assert.match(text, /판매금액 59,000원 \(1건\)/);
  assert.equal(reportKey(payload), 'reports/3/2026-W44.csv');
});
