// 핸들러 단위 테스트 — node --test (담당 VoC-100)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handler } from '../src/archive.js';

test('archive handler: 빈 이벤트도 처리한다', async () => {
  const result = await handler({});
  assert.ok(result);
});
