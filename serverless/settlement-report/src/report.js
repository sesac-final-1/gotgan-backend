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
import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { SESv2Client, SendEmailCommand } from '@aws-sdk/client-sesv2';

const won = (n) => `${Number(n).toLocaleString('ko-KR')}원`;

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
  return { subject, text };
}

export function reportKey(p) {
  return `reports/${p.storeId}/${p.week}.csv`;
}

/** 의존성(클라이언트 · 설정)을 받아 핸들러를 만든다 — 테스트는 가짜 클라이언트를 넣는다 */
export function createHandler({ ddb, s3, ses, env, log = console }) {
  async function processRecord(record) {
    const envelope = JSON.parse(record.body);
    if (envelope.eventType !== 'SettlementPaid') {
      log.warn(JSON.stringify({ msg: '처리하지 않는 이벤트 — 무시', eventType: envelope.eventType, eventId: envelope.eventId }));
      return;
    }
    const p = envelope.payload;
    if (!p.sellerEmail) {
      throw new Error('받는 주소 없음 — SettlementPaid payload 확인');   // 계약 누락은 재전달 → DLQ 알람으로 (order-notification 과 같게)
    }
    const keys = { PK: `STORE#${p.storeId}`, SK: `WEEK#${p.week}` };

    // ① 선점 — 없거나 SENT 가 아닐 때만(앞선 시도가 중간에 실패했으면 다시)
    try {
      await ddb.send(new PutCommand({
        TableName: env.TABLE,
        Item: { ...keys, status: 'PROCESSING', eventId: envelope.eventId, settlementId: p.settlementId,
          payoutAmt: p.payoutAmt, updatedAt: new Date().toISOString() },
        ConditionExpression: 'attribute_not_exists(SK) OR #s <> :sent',
        ExpressionAttributeNames: { '#s': 'status' },
        ExpressionAttributeValues: { ':sent': 'SENT' },
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
    const { subject, text } = buildEmail(p, env.FRONT_URL);
    try {
      await ses.send(new SendEmailCommand({
        FromEmailAddress: env.FROM_EMAIL,
        Destination: { ToAddresses: [p.sellerEmail] },
        Content: { Simple: { Subject: { Data: subject, Charset: 'UTF-8' }, Body: { Text: { Data: text, Charset: 'UTF-8' } } } },
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
