// SQS(order-events · store-events) → SES 알림 메일 + DynamoDB 발송 이력 (스펙 NTF-01~05) — 담당 jihoon-devstu
// 이벤트 봉투 형식: gotgan-config docs/events.md. 메일에 쓸 값은 이벤트 payload 에 있어야 한다(Lambda 는 클러스터 내부 API 를 부를 수 없음)
//
// 모든 단계가 실패할 수 있다고 보고 짠다 (settlement-report 와 같은 틀):
//  ① DynamoDB 선점 — PK USER#{id} · SK {occurredAt}#{eventId}(NTF-05), 이미 SENT 면 건너뜀(재전달 · 재발행에도 메일 1번)
//  ② SES 메일
//  ③ DynamoDB SENT 기록 (TTL 90일)
// 실패한 메시지만 batchItemFailures → SQS 재전달 → 3회 실패 시 DLQ(CloudWatch 알람). ② 성공 · ③ 실패면 메일이 한 번 더 갈 수 있다(최소 1회)
// 10/21 필드 추가 요청(ordererName · productName · 포인트 · userId 등) 전이라도 깨지지 않게, 없는 값은 메일에서 뺀다
import { DynamoDBClient, ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { SESv2Client, SendEmailCommand } from '@aws-sdk/client-sesv2';

const TTL_DAYS = 90;
const REAPPLY_DAYS = 30;   // 반려 후 재신청 제한 (스펙 STR-05)

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

/** 주문 이벤트 공통 — 인사 · 주문 상세 링크 */
const hello = (p) => `${has(p.ordererName) ? p.ordererName : '고객'}님,`;
const orderLink = (p, front) => `주문 상세: ${front}/mypage/orders/${p.orderNo}`;

/**
 * 이벤트별 메일 — to: 받는 주소, owner: 발송 이력 키(PK), mail: 제목 · 본문
 * 받는 주소가 없으면 보낼 수 없으므로 실패(재전달 → DLQ — 계약 누락을 알람으로 알게 됨)
 */
export const TEMPLATES = {
  OrderPaid: {
    to: (p) => p.ordererEmail,
    owner: (p) => (has(p.buyerId) ? `USER#${p.buyerId}` : `ORDER#${p.orderNo}`),
    mail: (p, front) => {
      const items = p.items ?? [];
      const named = items.filter((i) => has(i.productName));
      const lines = named.length
        ? named.map((i) => `· ${productLabel(i)}${has(i.quantity) ? ` × ${i.quantity}` : ''}${has(i.itemAmt) ? ` — ${won(i.itemAmt)}` : ''}`)
        : [`· 상품 ${items.length}건`];
      const earned = items.reduce((sum, i) => sum + Number(i.earnedPoint ?? 0), 0);
      return {
        subject: `[곳간] 주문이 완료되었습니다 — ${p.orderNo}`,
        text: [
          `${hello(p)} 주문이 완료되었습니다.`, '',
          `주문번호 ${p.orderNo}`, ...lines, '',
          `· 결제 금액 ${won(p.payAmt)}`,
          ...(Number(p.usedPoint ?? 0) > 0 ? [`· 사용 포인트 ${point(p.usedPoint)}`] : []),
          ...(earned > 0 ? [`· 적립 예정 포인트 ${point(earned)} (구매확정 시 적립)`] : []),
          '', orderLink(p, front),
        ].join('\n'),
      };
    },
  },

  OrderItemShipped: {
    to: (p) => p.ordererEmail,
    owner: (p) => (has(p.buyerId) ? `USER#${p.buyerId}` : `ORDER#${p.orderNo}`),
    mail: (p, front) => ({
      subject: has(p.productName) ? `[곳간] 상품이 출발했습니다 — ${p.productName}` : `[곳간] 주문하신 상품이 출발했습니다 — ${p.orderNo}`,
      text: [
        `${hello(p)} 주문하신 상품이 출발했습니다.`, '',
        ...(has(p.productName) ? [`· 상품 ${productLabel(p)}`] : []),
        `· 택배사 ${p.courier}`,
        `· 송장번호 ${p.trackingNo}`,
        '', orderLink(p, front),
      ].join('\n'),
    }),
  },
};

/** 취소 · 반품 완료 (NTF-03) — 문구만 다르고 같은 틀. done: 조사까지 붙인 "취소가" · "반품이" */
function refundTemplate(done) {
  return {
    to: (p) => p.ordererEmail,
    owner: (p) => (has(p.buyerId) ? `USER#${p.buyerId}` : `ORDER#${p.orderNo}`),
    mail: (p, front) => ({
      subject: has(p.productName) ? `[곳간] ${done} 완료되었습니다 — ${p.productName}` : `[곳간] ${done} 완료되었습니다 — ${p.orderNo}`,
      text: [
        `${hello(p)} 요청하신 ${done} 완료되었습니다.`, '',
        ...(has(p.productName) ? [`· 상품 ${productLabel(p)}`] : []),
        `· 환불 금액 ${won(p.refundAmt)}`,
        ...(Number(p.restoredPoint ?? 0) > 0 ? [`· 돌려드린 포인트 ${point(p.restoredPoint)}`] : []),
        ...(Number(p.revokedPoint ?? 0) > 0 ? [`· 회수된 적립 포인트 ${point(p.revokedPoint)}`] : []),
        '', '환불은 결제 수단에 따라 영업일 기준 3~5일 걸릴 수 있습니다.',
        orderLink(p, front),
      ].join('\n'),
    }),
  };
}
TEMPLATES.OrderItemCancelled = refundTemplate('취소가');
TEMPLATES.OrderItemReturned = refundTemplate('반품이');

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
  }),
};

TEMPLATES.StoreApplicationRejected = {
  to: (p) => p.sellerEmail,
  owner: applicantOwner,
  mail: (p, front) => {
    const reapply = plusDays(p.decidedAt, REAPPLY_DAYS);
    return {
      subject: `[곳간] 입점 신청 결과 안내 — ${p.storeName}`,
      text: [
        `${p.storeName} 입점 신청을 승인하지 못했습니다.`, '',
        `· 사유 ${p.reason}`, '',
        reapply ? `${reapply} 이후 다시 신청할 수 있습니다.` : `반려일로부터 ${REAPPLY_DAYS}일 뒤 다시 신청할 수 있습니다.`,
        `입점 신청: ${front}/seller/apply`,
      ].join('\n'),
    };
  },
};

TEMPLATES.StoreApplicationSupplementRequested = {
  to: (p) => p.sellerEmail,
  owner: applicantOwner,
  mail: (p, front) => ({
    subject: `[곳간] 입점 서류 보완을 요청드립니다 — ${p.storeName}`,
    text: [
      `${p.storeName} 입점 심사를 위해 서류 보완이 필요합니다.`, '',
      ...((p.requestedDocs ?? []).length ? ['· 보완할 서류', ...(p.requestedDocs).map((d) => `  - ${d}`)] : []),
      ...(has(p.message) ? [`· 안내 ${p.message}`] : []),
      '',
      has(p.dueAt) ? `${displayDateTime(p.dueAt)}까지 보완하지 않으면 신청이 자동 반려됩니다.` : '기한 안에 보완하지 않으면 신청이 자동 반려됩니다.',
      `서류 제출: ${front}/seller/apply`,
    ].join('\n'),
  }),
};

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
    const { subject, text } = template.mail(p, env.FRONT_URL);
    const at = now();

    // ① 선점 — 없거나 SENT 가 아닐 때만
    try {
      await ddb.send(new PutCommand({
        TableName: env.TABLE,
        Item: { ...keys, status: 'PROCESSING', eventType: envelope.eventType, subject, updatedAt: at.toISOString(),
          ttl: Math.floor(at.getTime() / 1000) + TTL_DAYS * 24 * 3600 },
        ConditionExpression: 'attribute_not_exists(SK) OR #s <> :sent',
        ExpressionAttributeNames: { '#s': 'status' },
        ExpressionAttributeValues: { ':sent': 'SENT' },
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
        Content: { Simple: { Subject: { Data: subject, Charset: 'UTF-8' }, Body: { Text: { Data: text, Charset: 'UTF-8' } } } },
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
