# 에러 코드 목록 (컨벤션 2-5)

코드 문자열 = `{도메인}_{상황}` = enum 상수명. **프론트와 공유된 코드는 이름을 바꾸지 않는다.** 새 상황은 새 코드.

| 접두어 | 서비스 | 코드 | HTTP | 메시지 |
|---|---|---|---|---|
| 공통 | 전체 | `INVALID_INPUT` · `UNAUTHORIZED` · `FORBIDDEN` · `NOT_FOUND` · `EXTERNAL_API_ERROR` · `GLOBAL_ERROR` | 400 · 401 · 403 · 404 · 502 · 500 | common/exception/CommonErrorCode |
| `AUTH` · `USER` · `POINT` | user | (담당자 작성) | | |
| `APPLY` · `STORE` | store | | | |
| `CATEGORY` · `PRODUCT` · `STOCK` | product | | | |
| `CART` · `ORDER` · `CLAIM` | order | | | |
| `PAYMENT` | payment | `PAYMENT_NOT_FOUND` · `PAYMENT_ORDER_CONFLICT` · `PAYMENT_NOT_COMPLETED` · `PAYMENT_AMOUNT_MISMATCH` · `PAYMENT_INVALID_STATUS` · `PAYMENT_CANCEL_AMOUNT_EXCEEDED` · `PAYMENT_WEBHOOK_INVALID_SIGNATURE` | 404 · 409 · 409 · 409 · 409 · 409 · 400 | 상황별 설명: `docs/api/payment.md` 6절 |
| `REVIEW` · `INQUIRY` | review | | | |
| `SETTLE` · `COMMISSION` | settlement | `SETTLE_STORE_NOT_FOUND` · `COMMISSION_NOT_FOUND` | 404 · 404 | 정산 스냅샷 없음(이벤트 재전달 대기) · 카테고리 수수료율 미등록 (2단계 API 추가 시 갱신) |
| — | admin | 하위 서비스의 code · message · HTTP 상태를 그대로 전달. 연결 실패 · 타임아웃만 `EXTERNAL_API_ERROR` | | |
