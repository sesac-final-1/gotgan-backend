// SQS(SettlementPaid) → 정산 명세서 CSV → S3 reports/{storeId}/{week}.csv → SES 메일(판매자 정산 화면 링크) + DynamoDB 발송 이력
// 담당 jihoon-devstu · 이벤트 봉투 형식: gotgan-config docs/events.md · 명세서 내용은 이벤트 payload 에 모두 있음(10/21 방식 B)
//
// 모든 단계가 실패할 수 있다고 보고 짠다:
//  ① DynamoDB 선점(PROCESSING) — 이미 SENT 면 건너뜀(같은 이벤트 재전달 · 아웃박스 재발행). PROCESSING 은 앞선 시도가 중간에 실패한 것이라 다시 처리
//  ② CSV 를 S3 에 올림 — 키가 정해져 있어 다시 올려도 같은 파일(덮어쓰기)
//  ③ SES 메일 — 판매자 정산 화면 링크(다운로드는 화면에서 10분 presigned, 스펙 STL-10)
//  ④ DynamoDB SENT 기록
// 어느 단계든 실패하면 그 메시지만 batchItemFailures → SQS 재전달 → 3회 실패 시 DLQ(CloudWatch 알람). ③ 성공 · ④ 실패면 메일이 한 번 더 갈 수 있다(최소 1회)
import { DynamoDBClient, ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { gunzipSync } from 'node:zlib';
import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { SESv2Client, SendEmailCommand } from '@aws-sdk/client-sesv2';

const won = (n) => `${Number(n).toLocaleString('ko-KR')}원`;

// ── 메일 HTML 틀 — order-notification/src/notify.js 의 mailHtml 과 같은 코드(Lambda 가 따로 배포돼 복사). 바꾸면 둘 다 바꾼다

/** HTML 본문에 넣는 값은 모두 이스케이프 — 스토어명은 판매자가 쓴 글자다 */
export function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

const COLOR = { ink: '#141414', ink2: '#3b3b3b', mute: '#8a8780', line: '#e6e3dc', cream: '#f6f4ef', point: '#ff5a1f', moss: '#3f6b4f', mossSoft: '#e5eee7', rust: '#b4371f', rustSoft: '#f8e4de', pointSoft: '#ffe9df' };
// 배지 색 — good: 완료 · 승인 · 출발 / info: 주문 · 보완요청 / bad: 취소 · 반품 · 반려
const TONE = { good: [COLOR.mossSoft, COLOR.moss], info: [COLOR.pointSoft, COLOR.point], bad: [COLOR.rustSoft, COLOR.rust] };
const FONT = "font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR',sans-serif;";
export const FOOTNOTE = { buyer: '문의는 곳간 마이페이지의 1:1 채팅을 이용해 주세요.', seller: '문의는 판매자 센터 채팅의 "운영팀에 문의"를 이용해 주세요.' };

/**
 * 곳간 메일 공통 HTML — 메일 앱 호환을 위해 표(table) + 인라인 스타일만(외부 CSS · 이미지 · 웹폰트 없음). 색은 쇼핑몰 화면과 같은 값
 * 값은 전부 글자로 받아 여기서 이스케이프한다. rows: [[이름, 값, 강조?]] · notes: [[제목, 문장]] · cta: { label, url }
 */
export function mailHtml({ badge, tone = 'good', title, intro = [], caption, rows = [], notes = [], cta, ctaHint, footnote = FOOTNOTE.buyer }) {
  const C = COLOR;
  const [badgeBg, badgeFg] = TONE[tone];
  const rowHtml = rows.map(([k, v, strong], i) => `
            <tr>
              <td style="${FONT}padding:12px 0;${i ? `border-top:1px solid ${C.line};` : ''}width:96px;font-size:13px;color:${C.mute};vertical-align:top;">${esc(k)}</td>
              <td style="${FONT}padding:12px 0;${i ? `border-top:1px solid ${C.line};` : ''}font-size:${strong ? 17 : 14}px;color:${strong ? C.point : C.ink};font-weight:${strong ? 800 : 600};">${esc(v)}</td>
            </tr>`).join('');
  const table = rows.length ? `
        <tr><td style="padding:16px 32px 0;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-top:1px solid ${C.ink};border-bottom:1px solid ${C.line};">
            ${caption ? `<tr><td colspan="2" style="${FONT}padding:12px 0 0;font-size:12px;color:${C.mute};">${esc(caption)}</td></tr>` : ''}${rowHtml}
          </table>
        </td></tr>` : '';
  const noteHtml = notes.length ? `
        <tr><td style="padding:20px 32px 0;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${C.cream};">
            <tr><td style="${FONT}padding:16px 18px;font-size:13px;line-height:1.7;color:${C.ink2};">
              ${notes.map(([h, body]) => `<b style="color:${C.ink};">${esc(h)}</b><br>${esc(body)}`).join('<br><br>\n              ')}
            </td></tr>
          </table>
        </td></tr>` : '';
  const ctaHtml = cta ? `
        <tr><td align="center" style="padding:28px 32px 32px;">
          <a href="${esc(cta.url)}" style="${FONT}display:inline-block;padding:14px 32px;background:${C.ink};color:#ffffff;font-size:15px;font-weight:700;text-decoration:none;">${esc(cta.label)} →</a>
          ${ctaHint ? `<p style="${FONT}margin:12px 0 0;font-size:12px;color:${C.mute};">${esc(ctaHint)}</p>` : ''}
        </td></tr>` : '<tr><td style="padding:16px 0 0;"></td></tr>';
  return `<!doctype html>
<html lang="ko">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title></head>
<body style="margin:0;padding:0;background:${C.cream};">
  <div style="display:none;max-height:0;overflow:hidden;">${esc(intro[0] ?? title)}</div>
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${C.cream};">
    <tr><td align="center" style="padding:32px 16px;">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border:1px solid ${C.line};">
        <tr><td style="${FONT}padding:24px 32px;border-bottom:3px solid ${C.ink};">
          <span style="font-size:24px;font-weight:900;letter-spacing:-1px;color:${C.ink};">곳간</span>
          <span style="font-size:10px;letter-spacing:3px;color:${C.mute};padding-left:6px;">GOTGAN</span>
        </td></tr>
        <tr><td style="${FONT}padding:32px 32px 8px;">
          <span style="display:inline-block;padding:4px 10px;background:${badgeBg};color:${badgeFg};font-size:12px;font-weight:700;">● ${esc(badge)}</span>
          <h1 style="margin:16px 0 0;font-size:24px;line-height:1.35;color:${C.ink};letter-spacing:-0.5px;">${esc(title)}</h1>
          ${intro.length ? `<p style="margin:8px 0 0;font-size:15px;line-height:1.6;color:${C.ink2};">${intro.map(esc).join('<br>')}</p>` : ''}
        </td></tr>${table}${noteHtml}${ctaHtml}
        <tr><td style="${FONT}padding:18px 32px;border-top:1px solid ${C.line};font-size:11px;line-height:1.6;color:${C.mute};">
          이 메일은 발신 전용입니다. ${esc(footnote)}<br>
          <span style="color:${C.point};font-weight:700;">곳간</span> — 작은 가게들의 큰 장터
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>`;
}


/** CSV 한 칸 — 쉼표 · 따옴표 · 줄바꿈이 있으면 따옴표로 감싸고 따옴표는 두 번 (RFC 4180) */
export function csvCell(value) {
  const s = value == null ? '' : String(value);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/**
 * 엑셀에서 읽기 쉬운 일시 — 이벤트 일시는 KST(+09:00) ISO 라 날짜와 시:분만 쓴다.
 * 2026-10-21T12:03:26.739816283+09:00 → 2026-10-21 12:03. 형식이 다르면 받은 값 그대로
 */
export function displayDateTime(iso) {
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(iso ?? '');
  return m ? `${m[1]} ${m[2]}` : (iso ?? '');
}

/** 항목이 많으면 itemsGzip(gzip + Base64)으로 온다 — SNS 한도 256KB (settlement SettlementPaidPayload, 11/5) */
export function withItems(payload) {
  if (payload.items || !payload.itemsGzip) return payload;
  return { ...payload, items: JSON.parse(gunzipSync(Buffer.from(payload.itemsGzip, 'base64')).toString('utf8')) };
}

/** 엑셀에서 한글이 깨지지 않게 UTF-8 BOM 을 붙인다 */
export function buildCsv(p) {
  const header = ['주문번호', '상품명', '구매확정일시', '판매금액', '수수료율(%)', '판매수수료', '결제수수료', '지급액'];
  const rows = (p.items ?? []).map((i) => [i.orderNo, i.productName, displayDateTime(i.confirmedAt), i.itemAmt, i.commissionRate,
    i.commissionAmt, i.paymentFeeAmt, i.payoutAmt]);
  const total = ['합계', `${(p.items ?? []).length}건`, '', p.salesAmt, '', p.commissionAmt, p.paymentFeeAmt, p.payoutAmt];
  const meta = [['스토어', p.storeName], ['정산 주차', `${p.week} (${p.weekStart} ~ ${p.weekEnd})`], ['지급일시', displayDateTime(p.paidAt)], []];
  return '﻿' + [...meta, header, ...rows, total].map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

export function buildEmail(p, frontUrl) {
  const subject = `[곳간] ${p.week} 정산금 ${won(p.payoutAmt)} 지급 완료`;
  const text = [
    `${p.storeName} 판매자님, ${p.week}(${p.weekStart} ~ ${p.weekEnd}) 정산금이 지급되었습니다.`,
    '',
    `· 판매금액 ${won(p.salesAmt)} (${(p.items ?? []).length}건)`,
    `· 판매수수료 ${won(p.commissionAmt)} · 결제수수료 ${won(p.paymentFeeAmt)}`,
    `· 지급액 ${won(p.payoutAmt)}`,
    '',
    `정산 명세서(CSV)는 판매자 센터 정산 화면에서 받을 수 있습니다: ${frontUrl}/seller/settlements`,
  ].join('\n');
  const count = (p.items ?? []).length;
  const html = mailHtml({
    badge: '지급완료', tone: 'good', title: '정산금이 지급되었습니다',
    intro: [`${p.storeName} 판매자님, 이번 주 정산금이 지급되었습니다.`],
    caption: `${p.week} (${p.weekStart} ~ ${p.weekEnd})`,
    rows: [
      ['판매금액', `${won(p.salesAmt)} (${count}건)`],
      ['판매수수료', `- ${won(p.commissionAmt)}`],
      ['결제수수료', `- ${won(p.paymentFeeAmt)}`],
      ...(p.paidAt ? [['지급일시', displayDateTime(p.paidAt)]] : []),
      ['지급액', won(p.payoutAmt), true],
    ],
    notes: [['정산 명세서', '상품주문별 명세서(CSV)는 판매자 센터 정산 화면에서 받을 수 있습니다.']],
    cta: { label: '정산 내역 보기', url: `${frontUrl}/seller/settlements` }, footnote: FOOTNOTE.seller,
  });
  return { subject, text, html };
}

export function reportKey(p) {
  return `reports/${p.storeId}/${p.week}.csv`;
}

/** 선점(PROCESSING)을 다른 실행 중으로 보는 시간 — Lambda 타임아웃(30초)보다 넉넉히 */
const PROCESSING_STALE_MS = 2 * 60 * 1000;

/** 의존성(클라이언트 · 설정)을 받아 핸들러를 만든다 — 테스트는 가짜 클라이언트를 넣는다 */
export function createHandler({ ddb, s3, ses, env, log = console }) {
  async function processRecord(record) {
    const envelope = JSON.parse(record.body);
    if (envelope.eventType !== 'SettlementPaid') {
      log.warn(JSON.stringify({ msg: '처리하지 않는 이벤트 — 무시', eventType: envelope.eventType, eventId: envelope.eventId }));
      return;
    }
    const p = withItems(envelope.payload);
    if (!p.sellerEmail) {
      throw new Error('받는 주소 없음 — SettlementPaid payload 확인');   // 계약 누락은 재전달 → DLQ 알람으로 (order-notification 과 같게)
    }
    const keys = { PK: `STORE#${p.storeId}`, SK: `WEEK#${p.week}` };

    // ① 선점 — 없거나, SENT 도 진행 중(PROCESSING)도 아닐 때만(앞선 시도가 중간에 죽었으면 2분 뒤 다시)
    try {
      await ddb.send(new PutCommand({
        TableName: env.TABLE,
        Item: { ...keys, status: 'PROCESSING', eventId: envelope.eventId, settlementId: p.settlementId,
          payoutAmt: p.payoutAmt, updatedAt: new Date().toISOString() },
        // 다른 실행이 막 선점한 행(PROCESSING, 갱신 2분 안)은 건너뜀 — SQS 가 같은 메시지를 동시에 두 번 줄 때 메일 2통 방지(11/3 점검).
        // 2분이 지난 PROCESSING 은 앞선 실행이 중간에 죽은 것(Lambda 타임아웃 30초)이라 다시 시도
        ConditionExpression: 'attribute_not_exists(SK) OR (#s <> :sent AND (#s <> :processing OR #u < :stale))',
        ExpressionAttributeNames: { '#s': 'status', '#u': 'updatedAt' },
        ExpressionAttributeValues: { ':sent': 'SENT', ':processing': 'PROCESSING', ':stale': new Date(Date.now() - PROCESSING_STALE_MS).toISOString() },
      }));
    } catch (e) {
      if (e instanceof ConditionalCheckFailedException || e?.name === 'ConditionalCheckFailedException') {
        log.info(JSON.stringify({ msg: '이미 발송한 정산 — 건너뜀', storeId: p.storeId, week: p.week, eventId: envelope.eventId }));
        return;
      }
      throw e;
    }

    // ② CSV
    const key = reportKey(p);
    await s3.send(new PutObjectCommand({ Bucket: env.BUCKET, Key: key, Body: buildCsv(p), ContentType: 'text/csv; charset=utf-8' }));

    // ③ 메일 — SES 가 받는 주소를 거절하면(MessageRejected: 샌드박스의 미인증 주소 등) 다시 보내도 같으므로 재전달하지 않고 REJECTED 로 남긴다
    //   (CSV 는 이미 올라가 있어 판매자 센터 정산 화면에서 받을 수 있다)
    const { subject, text, html } = buildEmail(p, env.FRONT_URL);
    try {
      await ses.send(new SendEmailCommand({
        FromEmailAddress: env.FROM_EMAIL,
        Destination: { ToAddresses: [p.sellerEmail] },
        Content: { Simple: { Subject: { Data: subject, Charset: 'UTF-8' }, Body: {
          Text: { Data: text, Charset: 'UTF-8' },
          Html: { Data: html, Charset: 'UTF-8' }, // 메일 앱이 HTML 을 고르고, 못 열면 텍스트
        } } },
      }));
    } catch (e) {
      if (e?.name !== 'MessageRejected') throw e;
      await ddb.send(new UpdateCommand({
        TableName: env.TABLE,
        Key: keys,
        UpdateExpression: 'SET #s = :rejected, s3Key = :k, updatedAt = :t',
        ExpressionAttributeNames: { '#s': 'status' },
        ExpressionAttributeValues: { ':rejected': 'REJECTED', ':k': key, ':t': new Date().toISOString() },
      }));
      log.warn(JSON.stringify({ msg: 'SES 가 받는 주소를 거절 — 재전달 안 함', storeId: p.storeId, week: p.week, eventId: envelope.eventId, detail: e?.message }));
      return;
    }

    // ④ 발송 완료
    await ddb.send(new UpdateCommand({
      TableName: env.TABLE,
      Key: keys,
      UpdateExpression: 'SET #s = :sent, s3Key = :k, sentAt = :t, updatedAt = :t',
      ExpressionAttributeNames: { '#s': 'status' },
      ExpressionAttributeValues: { ':sent': 'SENT', ':k': key, ':t': new Date().toISOString() },
    }));
    log.info(JSON.stringify({ msg: '정산 명세서 발송', storeId: p.storeId, week: p.week, settlementId: p.settlementId, key }));
  }

  return async (event) => {
    const failures = [];
    for (const record of event.Records ?? []) {
      try {
        await processRecord(record);
      } catch (e) {
        // 개인정보(이메일)는 남기지 않는다 — messageId 로 SQS · DLQ 에서 찾는다
        log.error(JSON.stringify({ msg: '정산 명세서 처리 실패 — 재전달', messageId: record.messageId, error: String(e?.name ?? e), detail: e?.message }));
        failures.push({ itemIdentifier: record.messageId });
      }
    }
    return { batchItemFailures: failures };
  };
}

// 실제 Lambda 진입점 — 클라이언트는 콜드 스타트 때 한 번만 만든다
let realHandler;
export const handler = async (event) => {
  realHandler ??= createHandler({
    ddb: DynamoDBDocumentClient.from(new DynamoDBClient({})),
    s3: new S3Client({}),
    ses: new SESv2Client({}),
    env: { TABLE: process.env.TABLE, BUCKET: process.env.BUCKET, FROM_EMAIL: process.env.FROM_EMAIL,
      FRONT_URL: process.env.FRONT_URL ?? 'https://gotgan-ashy.vercel.app' },
  });
  return realHandler(event);
};
