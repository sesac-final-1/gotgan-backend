// SQS(order-events · store-events) → SES 알림 메일 + DynamoDB 발송 이력 (스펙 NTF-01~05) — 담당 jihoon-devstu
// 이벤트 봉투 형식: gotgan-config docs/events.md. 메일에 쓸 값은 이벤트 payload 에 있어야 한다(Lambda 는 클러스터 내부 API 를 부를 수 없음)
//
// 모든 단계가 실패할 수 있다고 보고 짠다 (settlement-report 와 같은 틀):
//  ① DynamoDB 선점 — PK USER#{id} · SK {occurredAt}#{eventId}(NTF-05), 이미 SENT 면 건너뜀(재전달 · 재발행에도 메일 1번)
//  ② SES 메일
//  ③ DynamoDB SENT 기록 (TTL 90일)
// 실패한 메시지만 batchItemFailures → SQS 재전달 → 3회 실패 시 DLQ(CloudWatch 알람). ② 성공 · ③ 실패면 메일이 한 번 더 갈 수 있다(최소 1회)
// 10/21 필드 추가 요청(ordererName · productName · 포인트 · userId 등) 전이라도 깨지지 않게, 없는 값은 메일에서 뺀다
// 11/02 모든 메일을 HTML(곳간 카드 — mailHtml) + 텍스트로 — 배송 완료 메일 틀에 맞춤
import { DynamoDBClient, ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { SESv2Client, SendEmailCommand } from '@aws-sdk/client-sesv2';

const TTL_DAYS = 90;
const REAPPLY_DAYS = 30;   // 반려 후 재신청 제한 (스펙 STR-05)
const RETURN_WINDOW_DAYS = 7; // 배송완료 후 반품 · 교환 신청 기한 (order return-window)

const won = (n) => `${Number(n ?? 0).toLocaleString('ko-KR')}원`;
const point = (n) => `${Number(n ?? 0).toLocaleString('ko-KR')}P`;
const has = (v) => v !== undefined && v !== null && v !== '';
const productLabel = (p) => (has(p.optionName) ? `${p.productName} (${p.optionName})` : p.productName);

/** 이벤트 일시(KST ISO) → "2026-10-21 12:03" — 형식이 다르면 그대로 */
export function displayDateTime(iso) {
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(iso ?? '');
  return m ? `${m[1]} ${m[2]}` : (iso ?? '');
}

/** "2026-10-21" 에 n일 더한 날짜 — 재신청 가능일 안내 */
function plusDays(iso, days) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
  if (!m) return null;
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3] + days));
  return d.toISOString().slice(0, 10);
}

/** HTML 본문에 넣는 값은 모두 이스케이프 — 상품명 · 이름 · 사유는 판매자 · 구매자 · 관리자가 쓴 글자다 */
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

/** 주문 이벤트 공통 — 인사 · 주문 상세 링크 */
const buyerName = (p) => (has(p.ordererName) ? p.ordererName : '고객');
const hello = (p) => `${buyerName(p)}님,`;
const orderUrl = (p, front) => `${front}/mypage/orders/${p.orderNo}`;
const orderLink = (p, front) => `주문 상세: ${orderUrl(p, front)}`;
const orderCta = (p, front) => ({ label: '주문 상세 보기', url: orderUrl(p, front) });
const buyerOwner = (p) => (has(p.buyerId) ? `USER#${p.buyerId}` : `ORDER#${p.orderNo}`);
// 상품주문 하나의 상품 · 택배 줄 (출발 · 배송완료 공용)
const shipRows = (p) => [
  ...(has(p.productName) ? [['상품', productLabel(p)]] : []),
  ...(has(p.courier) || has(p.trackingNo) ? [['택배', [p.courier, p.trackingNo].filter(has).join(' · ')]] : []),
];

/**
 * 이벤트별 메일 — to: 받는 주소, owner: 발송 이력 키(PK), mail: 제목 · 텍스트 본문 · HTML 본문
 * 텍스트는 HTML 을 못 여는 메일 앱용 — 같은 내용. 받는 주소가 없으면 보낼 수 없으므로 실패(재전달 → DLQ — 계약 누락을 알람으로 알게 됨)
 */
export const TEMPLATES = {
  OrderPaid: {
    to: (p) => p.ordererEmail,
    owner: buyerOwner,
    mail: (p, front) => {
      const items = p.items ?? [];
      const named = items.filter((i) => has(i.productName));
      const itemText = (i) => `${productLabel(i)}${has(i.quantity) ? ` × ${i.quantity}` : ''}`;
      const lines = named.length
        ? named.map((i) => `· ${itemText(i)}${has(i.itemAmt) ? ` — ${won(i.itemAmt)}` : ''}`)
        : [`· 상품 ${items.length}건`];
      const earned = items.reduce((sum, i) => sum + Number(i.earnedPoint ?? 0), 0);
      const used = Number(p.usedPoint ?? 0);
      return {
        subject: `[곳간] 주문이 완료되었습니다 — ${p.orderNo}`,
        text: [
          `${hello(p)} 주문이 완료되었습니다.`, '',
          `주문번호 ${p.orderNo}`, ...lines, '',
          `· 결제 금액 ${won(p.payAmt)}`,
          ...(used > 0 ? [`· 사용 포인트 ${point(p.usedPoint)}`] : []),
          ...(earned > 0 ? [`· 적립 예정 포인트 ${point(earned)} (구매확정 시 적립)`] : []),
          '', orderLink(p, front),
        ].join('\n'),
        html: mailHtml({
          badge: '주문완료', tone: 'info', title: '주문이 완료되었습니다',
          intro: [`${buyerName(p)}님, 곳간에서 주문해 주셔서 감사합니다.`, '판매자가 상품을 보내면 송장번호와 함께 알려 드릴게요.'],
          caption: `주문번호 ${p.orderNo}`,
          rows: [
            ...(named.length ? named.map((i, k) => [k ? '' : '상품', `${itemText(i)}${has(i.itemAmt) ? ` · ${won(i.itemAmt)}` : ''}`]) : [['상품', `${items.length}건`]]),
            ...(used > 0 ? [['사용 포인트', point(p.usedPoint)]] : []),
            ...(earned > 0 ? [['적립 예정', `${point(earned)} (구매확정 시)`]] : []),
            ['결제 금액', won(p.payAmt), true],
          ],
          cta: orderCta(p, front),
        }),
      };
    },
  },

  OrderItemShipped: {
    to: (p) => p.ordererEmail,
    owner: buyerOwner,
    mail: (p, front) => ({
      subject: has(p.productName) ? `[곳간] 상품이 출발했습니다 — ${p.productName}` : `[곳간] 주문하신 상품이 출발했습니다 — ${p.orderNo}`,
      text: [
        `${hello(p)} 주문하신 상품이 출발했습니다.`, '',
        ...(has(p.productName) ? [`· 상품 ${productLabel(p)}`] : []),
        `· 택배사 ${p.courier}`,
        `· 송장번호 ${p.trackingNo}`,
        '', orderLink(p, front),
      ].join('\n'),
      html: mailHtml({
        badge: '배송시작', tone: 'good', title: '상품이 출발했습니다',
        intro: [`${buyerName(p)}님, 주문하신 상품이 출발했어요.`, '배송이 완료되면 다시 알려 드릴게요.'],
        caption: `주문번호 ${p.orderNo}`, rows: shipRows(p),
        cta: orderCta(p, front),
      }),
    }),
  },
};

/**
 * 배송 완료 (DLV-04 · 11/02 이경석 요청) — 상품주문마다, 교환 재배송이 배송완료되면 같은 상품주문에 한 번 더(eventId 다름 → 메일도 한 번 더)
 */
TEMPLATES.OrderItemDelivered = {
  to: (p) => p.ordererEmail,
  owner: buyerOwner,
  mail: (p, front) => {
    const rows = [...shipRows(p), ...(has(p.deliveredAt) ? [['배송완료', displayDateTime(p.deliveredAt)]] : [])];
    const confirm = has(p.autoConfirmAt)
      ? `${displayDateTime(p.autoConfirmAt)}에 자동으로 구매확정됩니다. 그 전에 직접 구매확정할 수 있습니다.`
      : '배송완료 7일 뒤 자동으로 구매확정됩니다. 그 전에 직접 구매확정할 수 있습니다.';
    const returns = `반품 · 교환은 배송완료 후 ${RETURN_WINDOW_DAYS}일 안에 신청할 수 있습니다.`;
    return {
      subject: has(p.productName) ? `[곳간] 배송이 완료되었습니다 — ${p.productName}` : `[곳간] 주문하신 상품의 배송이 완료되었습니다 — ${p.orderNo}`,
      text: [
        `${hello(p)} 주문하신 상품이 도착했습니다.`, '',
        ...rows.map(([k, v]) => `· ${k} ${v}`),
        '', confirm, returns, '',
        orderLink(p, front),
      ].join('\n'),
      html: mailHtml({
        badge: '배송완료', tone: 'good', title: '배송이 완료되었습니다',
        intro: [`${buyerName(p)}님, 주문하신 상품이 도착했어요.`, '받아 보신 상품은 마음에 드셨나요?'],
        caption: `주문번호 ${p.orderNo}`, rows,
        notes: [['구매확정 안내', confirm], ['반품 · 교환', returns]],
        cta: orderCta(p, front), ctaHint: '구매확정하면 적립 예정 포인트가 바로 들어와요.',
      }),
    };
  },
};

/** 취소 · 반품 완료 (NTF-03) — 문구만 다르고 같은 틀. done: 조사까지 붙인 "취소가" · "반품이", badge: 배지 글자 */
function refundTemplate(done, badge) {
  return {
    to: (p) => p.ordererEmail,
    owner: buyerOwner,
    mail: (p, front) => {
      const rows = [
        ...(has(p.productName) ? [['상품', productLabel(p)]] : []),
        ...(Number(p.restoredPoint ?? 0) > 0 ? [['돌려드린 포인트', point(p.restoredPoint)]] : []),
        ...(Number(p.revokedPoint ?? 0) > 0 ? [['회수된 적립', point(p.revokedPoint)]] : []),
        ['환불 금액', won(p.refundAmt), true],
      ];
      const refundNote = '환불은 결제 수단에 따라 영업일 기준 3~5일 걸릴 수 있습니다.';
      return {
        subject: has(p.productName) ? `[곳간] ${done} 완료되었습니다 — ${p.productName}` : `[곳간] ${done} 완료되었습니다 — ${p.orderNo}`,
        text: [
          `${hello(p)} 요청하신 ${done} 완료되었습니다.`, '',
          ...(has(p.productName) ? [`· 상품 ${productLabel(p)}`] : []),
          `· 환불 금액 ${won(p.refundAmt)}`,
          ...(Number(p.restoredPoint ?? 0) > 0 ? [`· 돌려드린 포인트 ${point(p.restoredPoint)}`] : []),
          ...(Number(p.revokedPoint ?? 0) > 0 ? [`· 회수된 적립 포인트 ${point(p.revokedPoint)}`] : []),
          '', refundNote,
          orderLink(p, front),
        ].join('\n'),
        html: mailHtml({
          badge, tone: 'bad', title: `${done} 완료되었습니다`,
          intro: [`${buyerName(p)}님, 요청하신 ${done} 완료되었습니다.`],
          caption: `주문번호 ${p.orderNo}`, rows,
          notes: [['환불 안내', refundNote]],
          cta: orderCta(p, front),
        }),
      };
    },
  };
}
TEMPLATES.OrderItemCancelled = refundTemplate('취소가', '취소완료');
TEMPLATES.OrderItemReturned = refundTemplate('반품이', '반품완료');

/** 입점 이벤트 공통 — 발송 이력은 신청 회원(없으면 신청서) 기준 */
const applicantOwner = (p) => (has(p.userId) ? `USER#${p.userId}` : `APPLICATION#${p.applicationId ?? p.storeId}`);

TEMPLATES.StoreApproved = {
  to: (p) => p.sellerEmail,
  owner: applicantOwner,
  mail: (p, front) => ({
    subject: `[곳간] 입점이 승인되었습니다 — ${p.storeName}`,
    text: [
      `${p.storeName} 입점이 승인되었습니다. 곳간에 오신 것을 환영합니다.`, '',
      '판매자 센터에서 상품을 등록하고 판매를 시작해 보세요.',
      `판매자 센터: ${front}/seller`,
    ].join('\n'),
    html: mailHtml({
      badge: '입점승인', tone: 'good', title: '입점이 승인되었습니다',
      intro: [`${p.storeName} 입점이 승인되었습니다.`, '곳간에 오신 것을 환영합니다!'],
      notes: [['다음 단계', '판매자 센터에서 상품을 등록하면 바로 판매가 시작됩니다.'], ['판매자 권한', '이미 로그인해 있다면 다시 로그인하면 판매자 센터가 열립니다.']],
      cta: { label: '판매자 센터로', url: `${front}/seller` }, footnote: FOOTNOTE.seller,
    }),
  }),
};

TEMPLATES.StoreApplicationRejected = {
  to: (p) => p.sellerEmail,
  owner: applicantOwner,
  mail: (p, front) => {
    const reapply = plusDays(p.decidedAt, REAPPLY_DAYS);
    const reapplyText = reapply ? `${reapply} 이후 다시 신청할 수 있습니다.` : `반려일로부터 ${REAPPLY_DAYS}일 뒤 다시 신청할 수 있습니다.`;
    return {
      subject: `[곳간] 입점 신청 결과 안내 — ${p.storeName}`,
      text: [
        `${p.storeName} 입점 신청을 승인하지 못했습니다.`, '',
        `· 사유 ${p.reason}`, '',
        reapplyText,
        `입점 신청: ${front}/seller/apply`,
      ].join('\n'),
      html: mailHtml({
        badge: '반려', tone: 'bad', title: '입점 신청 결과 안내',
        intro: [`${p.storeName} 입점 신청을 승인하지 못했습니다.`],
        rows: [['사유', p.reason], ...(reapply ? [['재신청 가능일', reapply]] : [])],
        notes: [['다시 신청하기', reapplyText]],
        cta: { label: '입점 신청 화면', url: `${front}/seller/apply` }, footnote: FOOTNOTE.seller,
      }),
    };
  },
};

TEMPLATES.StoreApplicationSupplementRequested = {
  to: (p) => p.sellerEmail,
  owner: applicantOwner,
  mail: (p, front) => {
    const docs = p.requestedDocs ?? [];
    const due = has(p.dueAt) ? `${displayDateTime(p.dueAt)}까지 보완하지 않으면 신청이 자동 반려됩니다.` : '기한 안에 보완하지 않으면 신청이 자동 반려됩니다.';
    return {
      subject: `[곳간] 입점 서류 보완을 요청드립니다 — ${p.storeName}`,
      text: [
        `${p.storeName} 입점 심사를 위해 서류 보완이 필요합니다.`, '',
        ...(docs.length ? ['· 보완할 서류', ...docs.map((d) => `  - ${d}`)] : []),
        ...(has(p.message) ? [`· 안내 ${p.message}`] : []),
        '', due,
        `서류 제출: ${front}/seller/apply`,
      ].join('\n'),
      html: mailHtml({
        badge: '보완요청', tone: 'info', title: '입점 서류 보완을 요청드립니다',
        intro: [`${p.storeName} 입점 심사를 위해 서류 보완이 필요합니다.`],
        rows: [...(docs.length ? [['보완할 서류', docs.join(', ')]] : []), ...(has(p.message) ? [['안내', p.message]] : []), ...(has(p.dueAt) ? [['기한', displayDateTime(p.dueAt)]] : [])],
        notes: [['기한 안내', due]],
        cta: { label: '보완 제출하기', url: `${front}/seller/apply` }, footnote: FOOTNOTE.seller,
      }),
    };
  },
};

/** 선점(PROCESSING)을 다른 실행 중으로 보는 시간 — Lambda 타임아웃(30초)보다 넉넉히 */
const PROCESSING_STALE_MS = 2 * 60 * 1000;

/** 의존성(클라이언트 · 설정)을 받아 핸들러를 만든다 — 테스트는 가짜 클라이언트를 넣는다 */
export function createHandler({ ddb, ses, env, log = console, now = () => new Date() }) {
  async function processRecord(record) {
    const envelope = JSON.parse(record.body);
    const template = TEMPLATES[envelope.eventType];
    if (!template) {
      log.warn(JSON.stringify({ msg: '알림 대상이 아닌 이벤트 — 무시', eventType: envelope.eventType, eventId: envelope.eventId }));
      return;
    }
    const p = envelope.payload ?? {};
    const to = template.to(p);
    if (!has(to)) {
      throw new Error(`받는 주소 없음 — ${envelope.eventType} payload 확인`);
    }
    const keys = { PK: template.owner(p), SK: `${envelope.occurredAt}#${envelope.eventId}` };
    const { subject, text, html } = template.mail(p, env.FRONT_URL);
    const at = now();

    // ① 선점 — 없거나, SENT 도 진행 중(PROCESSING)도 아닐 때만
    try {
      await ddb.send(new PutCommand({
        TableName: env.TABLE,
        Item: { ...keys, status: 'PROCESSING', eventType: envelope.eventType, subject, updatedAt: at.toISOString(),
          ttl: Math.floor(at.getTime() / 1000) + TTL_DAYS * 24 * 3600 },
        // 다른 실행이 막 선점한 행(PROCESSING, 갱신 2분 안)은 건너뜀 — SQS 가 같은 메시지를 동시에 두 번 줄 때 메일 2통 방지(11/3 점검).
        // 2분이 지난 PROCESSING 은 앞선 실행이 중간에 죽은 것(Lambda 타임아웃 30초)이라 다시 시도
        ConditionExpression: 'attribute_not_exists(SK) OR (#s <> :sent AND (#s <> :processing OR #u < :stale))',
        ExpressionAttributeNames: { '#s': 'status', '#u': 'updatedAt' },
        ExpressionAttributeValues: { ':sent': 'SENT', ':processing': 'PROCESSING', ':stale': new Date(at.getTime() - PROCESSING_STALE_MS).toISOString() },
      }));
    } catch (e) {
      if (e instanceof ConditionalCheckFailedException || e?.name === 'ConditionalCheckFailedException') {
        log.info(JSON.stringify({ msg: '이미 보낸 알림 — 건너뜀', eventType: envelope.eventType, eventId: envelope.eventId }));
        return;
      }
      throw e;
    }

    // ② 메일 — SES 가 받는 주소를 거절하면(MessageRejected: 샌드박스의 미인증 주소 등) 다시 보내도 같으므로 재전달하지 않고 REJECTED 로 남긴다
    try {
      await ses.send(new SendEmailCommand({
        FromEmailAddress: env.FROM_EMAIL,
        Destination: { ToAddresses: [to] },
        Content: { Simple: { Subject: { Data: subject, Charset: 'UTF-8' }, Body: {
          Text: { Data: text, Charset: 'UTF-8' },
          ...(html ? { Html: { Data: html, Charset: 'UTF-8' } } : {}), // 메일 앱이 HTML 을 고르고, 못 열면 텍스트
        } } },
      }));
    } catch (e) {
      if (e?.name !== 'MessageRejected') throw e;
      await ddb.send(new UpdateCommand({
        TableName: env.TABLE,
        Key: keys,
        UpdateExpression: 'SET #s = :rejected, updatedAt = :t',
        ExpressionAttributeNames: { '#s': 'status' },
        ExpressionAttributeValues: { ':rejected': 'REJECTED', ':t': now().toISOString() },
      }));
      log.warn(JSON.stringify({ msg: 'SES 가 받는 주소를 거절 — 재전달 안 함', eventType: envelope.eventType, eventId: envelope.eventId, detail: e?.message }));
      return;
    }

    // ③ 발송 완료
    await ddb.send(new UpdateCommand({
      TableName: env.TABLE,
      Key: keys,
      UpdateExpression: 'SET #s = :sent, sentAt = :t, updatedAt = :t',
      ExpressionAttributeNames: { '#s': 'status' },
      ExpressionAttributeValues: { ':sent': 'SENT', ':t': now().toISOString() },
    }));
    log.info(JSON.stringify({ msg: '알림 메일 발송', eventType: envelope.eventType, eventId: envelope.eventId, owner: keys.PK }));
  }

  return async (event) => {
    const failures = [];
    for (const record of event.Records ?? []) {
      try {
        await processRecord(record);
      } catch (e) {
        // 개인정보(이메일 · 이름)는 남기지 않는다 — messageId 로 SQS · DLQ 에서 찾는다
        log.error(JSON.stringify({ msg: '알림 처리 실패 — 재전달', messageId: record.messageId, error: String(e?.name ?? e), detail: e?.message }));
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
    ses: new SESv2Client({}),
    env: { TABLE: process.env.TABLE, FROM_EMAIL: process.env.FROM_EMAIL,
      FRONT_URL: process.env.FRONT_URL ?? 'https://gotgan-ashy.vercel.app' },
  });
  return realHandler(event);
};
