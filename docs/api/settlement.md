# settlement-service API 계약 (10/19)

담당 구지훈. 정산 적재 · 주간 집계 · 지급 · 보류 · 카테고리 수수료. 코드가 정본(springdoc `/swagger-ui.html`)이고, 이 문서는 **호출하는 쪽(admin-service · store 대시보드 · 프론트)이 알아야 할 규칙**을 정리합니다.
근거: 스펙 STL-01~10 · 05장 정산 규칙, 가이드라인 08장(인가) · 10장(이벤트 · 스케줄러), API 명세 시트.

## 공통

| 항목 | 값 |
|---|---|
| 주소 | `SETTLEMENT_SERVICE_URI=http://settlement-service:8085` |
| 인가 | `/internal/**` = 게이트웨이 라우트 없음(admin-service · store 만 호출). `/api/seller/settlements/**` = 게이트웨이 SELLER → 여기서 `X-Store-Id` 소유권 |
| 주차 | ISO 주차 `2026-W43` = 월~일(KST). 생략하면 지난주 |
| 금액 | `long` 원. 지급액 = 매출 − 판매수수료 − 결제수수료(2.2%), 상품주문마다 원 단위 내림 후 합계 |
| 율 | % 값 (`11.50` = 11.5%) |
| 상태 | `SCHEDULED`(지급예정) · `HELD`(지급보류) · `PAID`(지급완료) |
| 지급 예정일 | 주차 종료(일) + 4일 = 다음 주 목요일 |

## 1. 이벤트 수신 (SQS)

| 이벤트 | 처리 |
|---|---|
| `StoreApproved` | 스토어명 · 판매자 이메일 · 정산 계좌 스냅샷 |
| `StoreStatusChanged` | 지급 보류 판단 상태(옛 이벤트 무시) → 휴면 · 정지면 지급예정 정산서 **자동 보류**, 운영 재개면 **자동 보류만 자동 해제**(관리자 보류는 유지) |
| `OrderItemConfirmed` | 정산 대상 적재 — 구매확정일에 유효한 상위 카테고리(`topCategoryId`) 수수료율 스냅샷. **수수료율이 없으면 적재하지 않고 재전달 → DLQ** |

## 2. 수수료 — `/internal/commissions` (STL-01)

- `GET` → `[{categoryId, current: {rate, effectiveFrom} | null, reserved: [{rate, effectiveFrom}]}]`
- `PUT` `[{categoryId, rate, effectiveFrom?}]` → 위와 같은 목록
  - `effectiveFrom` 생략 = 다음 주 월요일 · 지난 날짜 400 `COMMISSION_PAST_EFFECTIVE_DATE` · `rate` 0~100
  - 같은 (카테고리, 적용 시작일)이 있으면 율만 수정, 이력은 지우지 않음

## 3. 정산 — `/internal/settlements` (admin-service)

| API | 요청 | 응답 |
|---|---|---|
| `GET /internal/settlements?week=&storeId=&status=` | | `{week, totals{count, salesAmt, commissionAmt, paymentFeeAmt, payoutAmt}, settlements[Summary]}` |
| `GET /internal/settlements/{id}` | | `{settlement: Summary, items[{orderItemId, orderNo, productName, confirmedAt, itemAmt, categoryId, rate, commission, paymentFee, payout}], history[{at, actor(SYSTEM/ADMIN), from, to, reason}]}` |
| `POST /internal/settlements/aggregate` | `{week?}` | `{week, created, updated, autoHeld}` — 멱등(스토어 × 주차 1건) |
| `POST /internal/settlements/pay` | `{ids[]}` | `{success[ids], failed[{id, code, message}]}` — 건마다 따로 커밋 |
| `PATCH /internal/settlements/{id}/hold` | `{hold, reason}` | 상세 — `hold=true` 면 reason 필수 |
| `GET /internal/stats/settlements?storeId=` | | storeId 있으면 `store{pendingItemCount, pendingPayoutAmt, nextPayout, recentPaid[8주]}`, 없으면 `platform{scheduledCount, scheduledAmt, heldCount, heldAmt, paidThisMonthAmt, commissionRevenueThisMonth}` |

`Summary` = `{id, storeId, storeName, week, weekStart, weekEnd, itemCount, salesAmt, commissionAmt, paymentFeeAmt, payoutAmt, status, holdReason, scheduledPayDate, paidAt, bank{bankName, accountNumber, accountHolder}}`

**집계 규칙**
- 매주 월 03:00(KST) 자동 — 클러스터가 07~21시에만 떠 있어 **시연은 "지금 집계"(`aggregate`)로**
- 집계 뒤 늦게 들어온 구매확정은 지급 전 정산서에 더해 다시 계산, 지급 완료된 주는 연결하지 않음(경고 로그)
- 휴면 · 정지 스토어는 집계 때 바로 `HELD`

**지급 규칙** — `SCHEDULED` · 스토어 운영중 · 계좌 있음일 때만. 지급 시 `payout`(계좌 스냅샷) 기록 → 커밋 후 `SettlementPaid` 발행
- 실패 코드: `SETTLE_INVALID_STATUS`(보류 · 이미 지급) · `SETTLE_STORE_PAYOUT_HELD` · `SETTLE_PAYOUT_ACCOUNT_MISSING` · `SETTLE_NOT_FOUND`

## 4. 판매자 — `/api/seller/settlements` (STL-07 · 10)

| API | 응답 |
|---|---|
| `GET /api/seller/settlements` | `{summary{nextPayout, paidRecent8WeeksAmt, pendingPayoutAmt, appliedRates[{categoryId, rate}]}, settlements[Summary]}` |
| `GET /api/seller/settlements/{id}` | 상세(위와 같음) — 다른 스토어 403 |
| `GET /api/seller/settlements/{id}/report` | `{url, expiresAt}` — presigned GET 7일, `reports/{storeId}/{week}.csv`. 지급 전 409 `SETTLE_REPORT_NOT_READY` |

- `X-Store-Id` 가 없으면 403, `X-User-Id` 가 없으면 401 (게이트웨이를 거치지 않은 요청)

## 5. 오류 코드

| 코드 | HTTP | 언제 |
|---|---|---|
| `SETTLE_NOT_FOUND` | 404 | 정산서 없음 |
| `SETTLE_STORE_NOT_FOUND` | 404 | 스토어 스냅샷 없음 (이벤트 재전달 대기 · 지급 불가) |
| `SETTLE_INVALID_STATUS` | 409 | 지금 상태에서 불가 (보류 중 지급 · 지급완료 보류 등) |
| `SETTLE_STORE_PAYOUT_HELD` | 409 | 휴면 · 정지 스토어 지급 |
| `SETTLE_PAYOUT_ACCOUNT_MISSING` | 409 | 정산 계좌 없음 |
| `SETTLE_HOLD_REASON_REQUIRED` | 400 | 보류 사유 없음 |
| `SETTLE_REPORT_NOT_READY` | 409 | 지급 전 명세서 요청 |
| `COMMISSION_NOT_FOUND` | 404 | 수수료율 미등록 (적재 시 — 재전달) |
| `COMMISSION_PAST_EFFECTIVE_DATE` | 400 | 지난 적용 시작일 |
| `INVALID_INPUT` (공통) | 400 | 주차 형식 · 율 범위 등 |
