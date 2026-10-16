# payment-service 내부 API 계약 (설계 — 10/16)

담당 구지훈. order-service(Saga, 담당 이경석)가 호출하는 `/internal/payments/**`와 포트원 웹훅의 **계약**입니다. 코드가 나오면 `payment.yaml`(springdoc 내보내기)을 함께 올리고, 이 문서는 동작 규칙 설명으로 남깁니다.
근거: 스펙 PAY-01~04 · 결제 상태, 가이드라인 06장(Saga) · 07장(결제 · paymentdb). 설계 문서끼리 어긋났던 부분은 10/16에 아래 내용으로 맞췄습니다(맨 아래 표).

## 공통

| 항목 | 값 |
|---|---|
| 주소 | `PAYMENT_SERVICE_URI=http://payment-service:8086` (ConfigMap) — 게이트웨이 라우트 없음, 클러스터 내부 전용 |
| 응답 | `ApiResponse<T>` 봉투 (컨벤션 2-4). 오류는 `code` · `message` |
| 금액 | `long`, 원 단위 |
| 시각 | ISO-8601 오프셋 포함 (Asia/Seoul) |
| 멱등 키 | 준비 · 검증 · 조회 = `orderNo`, 취소 = `sagaId` (`payment_cancel.saga_id` UNIQUE) |
| 포트원 | V2 · 테스트 채널 · 카드만. 포트원 호출은 트랜잭션 밖 · 서킷 브레이커 `portone` · 재시도는 GET만 |

### 응답 객체 `PaymentResponse`

```json
{
  "orderNo": "20261021-000123",
  "portonePaymentId": "pay-20261021-000123",
  "status": "PAID",
  "amount": 64400,
  "cancelledAmount": 0,
  "method": "CARD",
  "paidAt": "2026-10-21T09:12:33+09:00"
}
```

`status`: `READY` · `PAID` · `PARTIAL_CANCELLED` · `CANCELLED` · `FAILED` (스펙 "결제 상태"). `FAILED` · `CANCELLED`는 끝 상태입니다.

---

## 1. 결제 준비 — `POST /internal/payments` (PAY-01)

주문 Saga 4단계(`PAYMENT_READY`).

```json
{ "orderId": 123, "orderNo": "20261021-000123", "amount": 64400, "sagaId": "6c1f0a6e-..." }
```

| 경우 | 결과 |
|---|---|
| 처음 | `payment` READY 생성, `portonePaymentId = "pay-" + orderNo` → **201** `PaymentResponse` |
| 같은 orderNo · 같은 금액 재요청 | 기존 행 그대로 **200** (멱등) |
| 같은 orderNo · 다른 금액 | **409** `PAYMENT_ORDER_CONFLICT` |
| `amount < 1` | **400** `INVALID_INPUT` — **0원(전액 포인트) 주문은 order가 준비 · 검증을 건너뛰고 바로 PAID** (포트원은 0원 결제창 불가) |

order는 응답의 `portonePaymentId`를 브라우저에 넘겨 결제창 `paymentId`로 씁니다.

## 2. 결제 검증 — `POST /internal/payments/verify` (PAY-02)

브라우저 `POST /api/orders/{orderNo}/payment/confirm` → order가 호출. 주문 Saga 6단계(`PAYMENT_VERIFIED`).

```json
{ "sagaId": "6c1f0a6e-...", "orderNo": "20261021-000123", "amount": 64400 }
```

| 경우 | payment 처리 | 응답 | order 권장 처리 (결정은 이경석) |
|---|---|---|---|
| 이미 PAID | 없음 | **200** `PaymentResponse` | 주문 PAID로 진행 |
| 포트원 PAID · 금액 = `payment.amount` = 요청 `amount` | PAID · `pg_tx_id` · `method` · `paid_at` 기록 | **200** | 주문 PAID → `OrderPaid` |
| 포트원 PAID · 금액 불일치 | 포트원 **전액 자동 취소** → `payment_cancel`(사유 "결제 금액 불일치 자동 취소") → FAILED | **409** `PAYMENT_AMOUNT_MISMATCH` | 즉시 보상 |
| 포트원 미결제 (결제창 진행 중 · 닫음 · 실패) | 상태 변화 없음 | **409** `PAYMENT_NOT_COMPLETED` | **결제 대기 유지** — 15분 만료 스케줄러에서 확정 (웹훅이 늦게 반영할 수 있음) |
| 이미 FAILED · CANCELLED | 없음 | **409** `PAYMENT_INVALID_STATUS` | 보상 (이미 끝난 결제) |
| 결제 없음 | 없음 | **404** `PAYMENT_NOT_FOUND` | 보상 |
| 포트원 장애 · 타임아웃 | 상태 변화 없음 | **502** `EXTERNAL_API_ERROR` | 결과를 모르므로 결제 대기 유지 → 만료에서 확정 |

## 3. 결제 취소 — `POST /internal/payments/{orderNo}/cancel` (PAY-03)

```json
{ "sagaId": "9b2e...", "orderItemId": 456, "amount": 32200, "reason": "구매자 취소" }
```

`amount` · `orderItemId`를 **생략하면 전액 취소**(주문 Saga 보상), 넣으면 **부분 취소**(취소 · 반품 Saga 2단계).

**전액 취소 (주문 Saga 보상 — "되돌릴 게 없으면 아무 일도 안 함")**

| 현재 상태 | 처리 | 응답 |
|---|---|---|
| 결제 없음 (준비 전에 실패) | 없음 | **200** `data: null` |
| READY | 포트원 조회 → 결제돼 있으면(타임아웃 등) 포트원 전액 취소 + `payment_cancel` 기록 → **FAILED** | **200** |
| PAID · PARTIAL_CANCELLED | 남은 금액 포트원 취소 + `payment_cancel` → **CANCELLED** | **200** |
| FAILED · CANCELLED | 없음 | **200** (현재 상태) |

**부분 취소 (클레임 1건 = 취소 Saga 1건 = `sagaId` 1개)**

| 경우 | 응답 |
|---|---|
| PAID · PARTIAL_CANCELLED, `amount ≤ amount − cancelledAmount` | 포트원 부분 취소 → `payment_cancel` → 누적 취소액이 결제 금액보다 작으면 PARTIAL_CANCELLED, 같으면 CANCELLED · **200** |
| 같은 `sagaId` 재요청 | 기존 취소 결과 **200** (포트원 재호출 없음) |
| 남은 금액 초과 | **409** `PAYMENT_CANCEL_AMOUNT_EXCEEDED` |
| READY · FAILED · CANCELLED | **409** `PAYMENT_INVALID_STATUS` |
| 포트원 장애 · 타임아웃 | **502** `EXTERNAL_API_ERROR` — order는 이후 단계(재고 · 포인트)를 진행하지 않고 복구 스케줄러가 같은 `sagaId`로 재시도 |

- 취소 금액(배송비 배분 · 반품 배송비 차감)은 **order가 계산**해서 보냅니다. payment는 받은 금액만 취소합니다.
- 응답 `data`: `{ payment: PaymentResponse, cancel: { sagaId, orderItemId, cancelAmount, portoneCancellationId, cancelledAt } }` — 취소할 것이 없었으면 `cancel: null`, 결제 자체가 없으면 `data: null`

## 4. 결제 상태 조회 — `GET /internal/payments?orderNo=` (ORD-07 결제 대기 만료)

포트원을 부르지 않고 DB 상태만 돌려줍니다. **200** `PaymentResponse` · 없으면 **404** `PAYMENT_NOT_FOUND`. 재시도 가능(GET).
만료 스케줄러: PAID면 주문 PAID 마무리, 그 밖(READY 등)이면 보상 → 3번 전액 취소가 포트원을 확인해 정리합니다.

## 5. 포트원 웹훅 — `POST /api/payments/webhook` (PAY-04)

- 게이트웨이 permitAll(POST만, 이미 설정됨). 포트원 콘솔 등록 주소: `https://gotgan-ashy.vercel.app/api/payments/webhook`
- 서명 검증: Standard Webhooks 형식(`webhook-id` · `webhook-timestamp` · `webhook-signature`, HMAC-SHA256, 시크릿 `PORTONE_WEBHOOK_SECRET`, 시각 허용 5분) — **구현 시 포트원 V2 문서로 재확인**. 실패하면 **400**, 처리 안 함
- 본문은 "확인 요청"으로만 사용: `data.paymentId`로 `payment`를 찾아 **포트원을 다시 조회**하고 2번과 같은 비교(기대 금액 = `payment.amount`). 모르는 type · paymentId는 **200** 무시
- **FAILED 인데 포트원이 PAID**(결제 대기 만료 · 보상 후 사용자가 늦게 결제 완료) → 포트원 전액 자동 취소 + `payment_cancel`(사유 "결제 실패 처리 후 승인된 결제 자동 취소"), 상태는 FAILED 유지 — 주문 없는 결제가 남지 않게
- 처리 중 오류는 **5xx** → 포트원이 재전송. `version`(@Version)으로 확인 요청과 웹훅이 동시에 와도 한 번만 반영

## 6. 오류 코드 (`PaymentErrorCode`, `docs/api/error-codes.md`)

| 코드 | HTTP | 언제 |
|---|---|---|
| `PAYMENT_NOT_FOUND` | 404 | orderNo로 결제 없음 (검증 · 부분 취소 · 조회) |
| `PAYMENT_ORDER_CONFLICT` | 409 | 같은 orderNo로 다른 금액 준비 요청 |
| `PAYMENT_NOT_COMPLETED` | 409 | 포트원 미결제 |
| `PAYMENT_AMOUNT_MISMATCH` | 409 | 금액 불일치 (자동 취소 후) |
| `PAYMENT_INVALID_STATUS` | 409 | 지금 상태에서 할 수 없는 요청 |
| `PAYMENT_CANCEL_AMOUNT_EXCEEDED` | 409 | 남은 금액보다 큰 부분 취소 |
| `PAYMENT_WEBHOOK_INVALID_SIGNATURE` | 400 | 웹훅 서명 검증 실패 |
| `EXTERNAL_API_ERROR` (공통) | 502 | 포트원 장애 · 타임아웃 · 서킷 OPEN |

order는 409 · 404를 자기 도메인 코드로 바꾸고 **서킷 실패로 세지 않습니다**(가이드라인 06장 — `ignore-exceptions`).

## 7. order 쪽 준비 사항 (이경석)

- 서킷 브레이커 인스턴스 이름은 가이드라인 06장 규칙대로 **`payment`**(대상 서비스 이름). 지금 order `application.yml`의 `paymentClient`는 템플릿 값이라 맞춰 주세요. payment 쪽도 `portoneClient` → `portone`으로 바꿉니다
- 0원 주문: 결제 준비 · 검증 단계 생략 → 바로 주문 PAID (위 1번)
- `PAYMENT_NOT_COMPLETED` · `EXTERNAL_API_ERROR` 때는 결제 대기를 유지하고 만료 스케줄러에서 4번 조회로 확정하는 것을 권장 (2번 표)

## 10/16 설계 정리 — 문서끼리 어긋났던 부분

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 취소 경로 (`{paymentId}` vs `{orderNo}`) | `/internal/payments/{orderNo}/cancel` — order는 orderNo만 앎 |
| 2 | 취소 멱등 키 (claimId vs sagaId) | `sagaId` (DDL `uk_payment_cancel_saga_id`). 클레임 1건 = 취소 Saga 1건이라 claimId 기준 멱등과 같은 효과 |
| 3 | 준비 멱등 키 (sagaId — 컬럼 없음) | `orderNo` (`uk_payment_order_no`). 요청에 `orderId` 포함(`order_id` NOT NULL) |
| 4 | READY 보상 결과 (CANCELLED vs FAILED) | `FAILED` (스펙 상태 전이 기준, 만료와 같은 결과) |
| 5 | 금액 불일치 응답 ("FAILED 응답" vs 409) | 자동 취소 → `payment_cancel` → FAILED → 409 `PAYMENT_AMOUNT_MISMATCH` |
| 6 | 미결제 처리 (자동 취소?) | 취소할 결제가 없으므로 상태 변화 없이 409 `PAYMENT_NOT_COMPLETED` |
| 7 | 상태 조회 API (가이드라인에만 있음) | `GET /internal/payments?orderNo=` 스펙에 추가 |
| 8 | 서킷 브레이커 이름 (`payment`/`portone` vs `paymentClient`/`portoneClient`) | 가이드라인 규칙(대상 서비스 이름) — 코드를 맞춤 |
| 9 | 0원 결제 | order가 결제 단계 생략, payment는 1원 이상 |
| 10 | 취소 금액 배송비 배분 | order 계산, payment는 받은 금액만 |
| 11 | 결제수단 | 카드만 (프론트 목업 6종은 결제창 연결 때 정리) |
| 12 | 웹훅 주소 · 시크릿 이름 | `gotgan-ashy.vercel.app` · `payment-service-secret` |
