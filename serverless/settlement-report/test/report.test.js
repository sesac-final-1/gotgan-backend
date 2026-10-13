// 핸들러 단위 테스트 — node --test (담당 jihoon-devstu)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handler } from '../src/report.js';

test('report handler: 빈 이벤트도 처리한다', async () => {
  const result = await handler({ Records: [] });
  assert.ok(result);
});
