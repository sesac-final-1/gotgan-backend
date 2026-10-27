// 업로드 검사 단위 테스트 — node --test (담당 jihoon-devstu). AWS 클라이언트는 가짜로 넣는다
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RULES, createHandler, extensionOf, inspect } from '../src/guard.js';

const ENV = { IMAGES_BUCKET: 'sesac-gojigi-images-test', SELLER_DOCS_BUCKET: 'sesac-gojigi-seller-docs-test', ALERT_TOPIC_ARN: 'arn:aws:sns:ap-northeast-2:1:alerts' };
const bytes = (...v) => Uint8Array.from(v);
const text = (s) => Uint8Array.from([...s].map((c) => c.charCodeAt(0)));
const JPG = bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0x10);
const PNG = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0);
const WEBP = text('RIFF\0\0\0\0WEBPVP8 ');
const PDF = text('%PDF-1.7\n');
const HTML = text('<html><script>');

test('확장자 — 소문자, jpeg 는 jpg, 없으면 null', () => {
  assert.equal(extensionOf('products/1/a.JPEG'), 'jpg');
  assert.equal(extensionOf('applications/7/b.pdf'), 'pdf');
  assert.equal(extensionOf('products/1/noext'), null);
});

test('images — jpg · png · webp 는 내용이 맞으면 통과', () => {
  assert.deepEqual(inspect(RULES.images, 'products/1/a.jpg', JPG), { ok: true });
  assert.deepEqual(inspect(RULES.images, 'reviews/4/b.png', PNG), { ok: true });
  assert.deepEqual(inspect(RULES.images, 'stores/2/c.webp', WEBP), { ok: true });
  assert.deepEqual(inspect(RULES.images, 'products/seed/p101-1.jpg', JPG), { ok: true });
});

test('images — 확장자만 바꾼 파일 · pdf · 빈 파일 · 다른 경로는 거절', () => {
  assert.equal(inspect(RULES.images, 'products/1/a.jpg', HTML).reason, '내용이 jpg 형식이 아님');
  assert.equal(inspect(RULES.images, 'products/1/a.png', JPG).reason, '내용이 png 형식이 아님');
  assert.equal(inspect(RULES.images, 'products/1/a.pdf', PDF).reason, '허용하지 않는 확장자(pdf)');
  assert.equal(inspect(RULES.images, 'products/1/a.jpg', bytes()).reason, '빈 파일');
  assert.equal(inspect(RULES.images, 'tmp/a.jpg', JPG).reason, '허용하지 않는 경로');
});

test('seller-docs — pdf · jpg · png 통과, webp · 위장 pdf 거절', () => {
  assert.deepEqual(inspect(RULES['seller-docs'], 'applications/4/a.pdf', PDF), { ok: true });
  assert.deepEqual(inspect(RULES['seller-docs'], 'applications/4/b.png', PNG), { ok: true });
  assert.equal(inspect(RULES['seller-docs'], 'applications/4/c.webp', WEBP).reason, '허용하지 않는 확장자(webp)');
  assert.equal(inspect(RULES['seller-docs'], 'applications/4/d.pdf', HTML).reason, '내용이 pdf 형식이 아님');
});

/** 보낸 명령을 기록하는 가짜 클라이언트 — head: GetObject 가 돌려줄 앞부분, getError: GetObject 예외 */
function fake({ head = JPG, getError } = {}) {
  const calls = [];
  const client = (name) => ({
    send: async (cmd) => {
      const type = cmd.constructor.name;
      calls.push({ client: name, type, input: cmd.input });
      if (type === 'GetObjectCommand') {
        if (getError) throw getError;
        return { Body: { transformToByteArray: async () => head } };
      }
      return {};
    },
  });
  const quiet = { info() {}, warn() {}, error() {} };
  return { calls, handler: createHandler({ s3: client('s3'), sns: client('sns'), env: ENV, log: quiet }) };
}
const created = (bucket, key) => ({ 'detail-type': 'Object Created', source: 'aws.s3', detail: { bucket: { name: bucket }, object: { key, size: 10 } } });

test('정상 파일 — 앞 16바이트만 읽고 아무것도 지우지 않음', async () => {
  const { calls, handler } = fake({ head: PNG });
  const r = await handler(created(ENV.IMAGES_BUCKET, 'products/1/a.png'));

  assert.deepEqual(r, { checked: true, deleted: false });
  assert.deepEqual(calls.map((c) => c.type), ['GetObjectCommand']);
  assert.equal(calls[0].input.Range, 'bytes=0-15');
});

test('위장 파일 — 객체 삭제 후 알림 토픽에 버킷 · 키 · 이유', async () => {
  const { calls, handler } = fake({ head: HTML });
  const r = await handler(created(ENV.SELLER_DOCS_BUCKET, 'applications/4/a.pdf'));

  assert.equal(r.deleted, true);
  assert.deepEqual(calls.map((c) => c.type), ['GetObjectCommand', 'DeleteObjectCommand', 'PublishCommand']);
  assert.deepEqual(calls[1].input, { Bucket: ENV.SELLER_DOCS_BUCKET, Key: 'applications/4/a.pdf' });
  assert.equal(calls[2].input.TopicArn, ENV.ALERT_TOPIC_ARN);
  assert.match(calls[2].input.Message, /applications\/4\/a\.pdf[\s\S]*내용이 pdf 형식이 아님/);
});

test('이미 없는 객체(재전달) · 다른 버킷은 건너뜀, 그 밖의 S3 오류는 던져서 재시도', async () => {
  const gone = Object.assign(new Error('gone'), { name: 'NoSuchKey' });
  assert.deepEqual(await fake({ getError: gone }).handler(created(ENV.IMAGES_BUCKET, 'products/1/a.jpg')), { checked: false });
  assert.deepEqual(await fake().handler(created('other-bucket', 'a.jpg')), { checked: false });

  const denied = Object.assign(new Error('denied'), { name: 'AccessDenied' });
  await assert.rejects(fake({ getError: denied }).handler(created(ENV.IMAGES_BUCKET, 'products/1/a.jpg')), /denied/);
});
