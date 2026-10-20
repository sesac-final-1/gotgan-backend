# 에러 코드 목록 (컨벤션 2-5)

코드 문자열 = `{도메인}_{상황}` = enum 상수명. **프론트와 공유된 코드는 이름을 바꾸지 않는다.** 새 상황은 새 코드.

| 접두어 | 서비스 | 코드 | HTTP | 메시지 |
|---|---|---|---|---|
| 공통 | 전체 | `INVALID_INPUT` · `UNAUTHORIZED` · `FORBIDDEN` · `NOT_FOUND` · `METHOD_NOT_ALLOWED` · `EXTERNAL_API_ERROR` · `GLOBAL_ERROR` | 400 · 401 · 403 · 404 · 405 · 502 · 500 | common/exception/CommonErrorCode (이관한 서비스는 libs/common-web) |
| `AUTH` · `USER` · `POINT` | user | `AUTH_INVALID_CREDENTIALS` · `AUTH_ACCOUNT_NOT_LINKED` · `AUTH_ACCOUNT_DISABLED` · `AUTH_EMAIL_DUPLICATE` · `AUTH_PASSWORD_POLICY_VIOLATION` | 401 · 401 · 403 · 409 · 400 | 로그인(`POST /api/auth/login`) — 비밀번호 불일치 · 없는 계정 · 임시 잠금 / Keycloak 계정에 user_id 미연결 / 이용 정지. 회원가입(`POST /api/auth/signup`) — 이미 가입된 이메일(탈퇴 회원 포함) / 비밀번호 정책 위반(8자 · 영문 · 특수문자 · 이메일과 다름). 회원 탈퇴(`DELETE /api/users/me`) — 없는 회원 · 이미 탈퇴는 `USER_NOT_FOUND`(404), 판매자 스토어 미폐점은 `USER_SELLER_STORE_NOT_CLOSED`(409), 게이트웨이 헤더 없음은 공통 `UNAUTHORIZED`(401). Keycloak 장애는 공통 `EXTERNAL_API_ERROR`(502) |
| `APPLY` · `STORE` | store | | | |
| `CATEGORY` · `PRODUCT` · `STOCK` | product | | | |
| `CART` · `ORDER` · `CLAIM` | order | | | |
| `PAYMENT` | payment | `PAYMENT_NOT_FOUND` · `PAYMENT_ORDER_CONFLICT` · `PAYMENT_NOT_COMPLETED` · `PAYMENT_AMOUNT_MISMATCH` · `PAYMENT_INVALID_STATUS` · `PAYMENT_CANCEL_AMOUNT_EXCEEDED` · `PAYMENT_CANCEL_PENDING` · `PAYMENT_WEBHOOK_INVALID_SIGNATURE` | 404 · 409 · 409 · 409 · 409 · 409 · 409 · 400 | 상황별 설명: `docs/api/payment.md` 6절 |
| `REVIEW` · `INQUIRY` | review | | | |
| `SETTLE` · `COMMISSION` | settlement | `SETTLE_NOT_FOUND` · `SETTLE_STORE_NOT_FOUND` · `SETTLE_INVALID_STATUS` · `SETTLE_STORE_PAYOUT_HELD` · `SETTLE_PAYOUT_ACCOUNT_MISSING` · `SETTLE_HOLD_REASON_REQUIRED` · `SETTLE_REPORT_NOT_READY` · `COMMISSION_NOT_FOUND` · `COMMISSION_PAST_EFFECTIVE_DATE` | 404 · 404 · 409 · 409 · 409 · 400 · 409 · 404 · 400 | 상황별 설명: `docs/api/settlement.md` |
| — | admin | 하위 서비스의 code · message · HTTP 상태를 그대로 전달. 연결 실패 · 타임아웃만 `EXTERNAL_API_ERROR` | | |
