// SQS(order-events · store-events) → SES 메일(주문 확인 · 배송 시작 · 취소/반품 · 입점 승인) + DynamoDB 멱등 · 이력
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
