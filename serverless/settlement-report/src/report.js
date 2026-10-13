// SQS(SettlementPaid) → 정산 명세서 CSV → S3 reports → SES 로 판매자에게 다운로드 링크(presigned 7일) + DynamoDB 발송 이력
// 이벤트 봉투 형식: docs/events.md. 같은 eventId 가 두 번 와도 한 번만 처리 (DynamoDB 조건부 PutItem)
// TODO jihoon-devstu: 레코드별 처리 · 실패한 messageId 만 batchItemFailures 로 반환 (나머지는 삭제됨)
export const handler = async (event) => {
  const failures = [];
  for (const record of event.Records ?? []) {
    try {
      const envelope = JSON.parse(record.body);
      console.log('received', envelope.eventType, envelope.eventId);
    } catch (e) {
      console.error('failed', record.messageId, e);
      failures.push({ itemIdentifier: record.messageId });
    }
  }
  return { batchItemFailures: failures };
};
