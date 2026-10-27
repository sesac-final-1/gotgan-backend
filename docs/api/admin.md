# admin-service API — 신고 · 조치 (10/26 B안) · 알림 센터 (10/27)

- 담당: 구지훈. 다른 관리자 API(회원 · 정산 · 수수료 · 카테고리 · 대시보드 · 입점 · 스토어)는 하위 서비스 계약(user · settlement · product · store.md)을 그대로 전달하므로 여기에는 **admin 이 직접 갖는 신고 · 알림 센터만** 적는다
- 근거: ADR-0001 "변경 (10/26)" — 회원 신고 범위 복귀 · admin DB 예외(신고 · 조치 이력만), 노션 확인 요청 「신고 · 리뷰 블라인드 (B안)」
- 저장: admindb `report`(신고자 · 대상당 1건) · `moderation_action`(처리 1번 = 1행). 리뷰 블라인드 상태는 review, 회원 정지 상태는 user 가 주인

## 공통

| 항목 | 규칙 |
|---|---|
| 대상 `targetType` | `REVIEW` · `USER` |
| 신고 상태 | `OPEN`(처리 대기) → 관리자 조치 한 번에 대상의 열린 신고가 모두 `RESOLVED`(블라인드 · 정지) 또는 `DISMISSED`(해제 · 기각) |
| 대상 서비스 | 리뷰 조회 · 블라인드 = review `GET` · `PATCH /internal/reviews/{id}(/blind)` (서킷 `review`), 회원 조회 · 정지 = user `GET` · `PATCH /internal/users/{id}(/status)` (서킷 `user`). 오류는 HTTP 상태 · code 그대로, 장애는 502 `EXTERNAL_API_ERROR` |
| review 배포 전 | `REVIEW_CLIENT_MOCK=true`(기본) — 어떤 리뷰 ID 든 있는 것으로 보고 블라인드 상태만 메모리에. 배포 후 ConfigMap `REVIEW_CLIENT_MOCK=false` |
| 자동 처리 | 없음 — 신고가 쌓여도 자동 임시 블라인드 · 처리 메일 없음(관리자 수동 처리만) |

### 신고 사유 `reasonCode`

| 코드 | 리뷰 | 회원 | 뜻 |
|---|---|---|---|
| `SPAM` | ✅ | ✅ | 광고 · 도배 |
| `ABUSE` | ✅ | ✅ | 욕설 · 비방 |
| `FALSE_INFO` | ✅ | | 허위 · 구매와 무관한 리뷰 |
| `PRIVACY` | ✅ | | 개인정보 노출 |
| `OBSCENE` | ✅ | | 음란 · 부적절한 사진 |
| `FRAUD` | | ✅ | 거래 사기 · 결제 도용 의심 |
| `SELLER_REQUEST` | ✅ | | 판매자 "관리자 검토 요청" — SELLER · 내 스토어 리뷰만 |
| `ETC` | ✅ | ✅ | 기타 |

## 1. 신고 접수 — `POST /api/reports` (BUYER · SELLER)

게이트웨이 `/api/reports/**` → admin-service, 역할 `BUYER` · `SELLER`.

```json
// 요청
{ "targetType": "REVIEW", "targetId": 31, "reasonCode": "ABUSE", "detail": "욕설이 있어요" }
// 201 data
{ "reportId": 7, "status": "OPEN" }
```

| 경우 | 응답 |
|---|---|
| 정상 — 대상 서비스에 있는 대상, 신고 당시 요약(리뷰 본문 앞 200자 · 회원 이름 (닉네임)) 저장 | **201** |
| 같은 사람이 같은 대상을 다시 신고 | 409 `REPORT_DUPLICATED` |
| 본인(회원) · 내가 쓴 리뷰 | 400 `REPORT_SELF` |
| 대상에 없는 사유 · 구매자가 `SELLER_REQUEST` | 400 `REPORT_REASON_NOT_ALLOWED` |
| 판매자 검토 요청인데 다른 스토어 리뷰 (`X-Store-Id` ≠ 리뷰 `storeId`) | 403 `REPORT_NOT_OWN_STORE` |
| 없는 리뷰 · 회원 | review `REVIEW_NOT_FOUND` · user `USER_NOT_FOUND` 404 그대로 |
| 형식 오류 (`detail` 500자 초과 등) | 400 `INVALID_INPUT` |

- `reporterRole` 은 토큰 역할로 정한다 — `SELLER` 역할이 있으면 SELLER, 아니면 BUYER

## 2. 신고 목록 — `GET /api/admin/reports?targetType=` (ADMIN)

대상별 묶음, **페이징 없이 전체 배열**(컨벤션 4-7). 처리 대기(`openCount`) 많은 순 → 최근 신고 순. `targetType` 생략 = 전체.

```json
[ { "targetType": "REVIEW", "targetId": 31, "summary": "광고 링크 …", "openCount": 2, "totalCount": 2,
    "reasonCounts": { "SPAM": 1, "ABUSE": 1 }, "lastReportedAt": "2026-10-26T15:00:00+09:00",
    "lastAction": null } ]
```

- `summary` = 가장 최근 신고 때의 대상 요약, `lastAction` = 마지막 조치(아래 3절 `actions[]` 항목 모양)

## 3. 신고 상세 — `GET /api/admin/reports/{targetType}/{targetId}` (ADMIN)

```json
{ "targetType": "USER", "targetId": 12,
  "target": { "userId": 12, "name": "…", "status": "SUSPENDED", "...": "user 상세 그대로" }, "targetError": null,
  "reports": [ { "reportId": 7, "reporterId": 4, "reporterRole": "BUYER", "reasonCode": "FRAUD", "detail": null,
                 "targetSummary": "홍길동 (길동이)", "status": "RESOLVED", "createdAt": "…" } ],
  "actions": [ { "moderationActionId": 3, "action": "SUSPEND", "reason": "결제 도용 의심", "suspendDays": 7,
                 "adminId": 1, "reportCount": 1, "createdAt": "…" } ] }
```

- `target` 은 대상 서비스의 **현재 값**(리뷰: review 조회, 회원: user 상세). 불러오지 못하면 `null` + `targetError`(메시지) — 신고 · 조치 기록은 그대로 보여 준다
- 신고가 없어도 200(빈 배열) — 회원 상세 화면의 제재 이력으로도 쓴다

## 4. 신고 처리 — `POST /api/admin/reports/{targetType}/{targetId}/resolve` (ADMIN)

```json
{ "action": "SUSPEND", "reason": "결제 도용 의심", "days": 7 }
// 200 data
{ "action": { "moderationActionId": 3, "action": "SUSPEND", "...": "…" }, "closedReports": 1,
  "target": { "...": "대상 서비스 응답 그대로 (기각이면 null)" } }
```

| `action` | 대상 | 대상 서비스 호출 | 열린 신고 |
|---|---|---|---|
| `BLIND` | REVIEW | review `PATCH /blind {blinded: true, reason}` | RESOLVED |
| `UNBLIND` | REVIEW | review `PATCH /blind {blinded: false, reason}` | DISMISSED |
| `SUSPEND` | USER | user `PATCH /status {status: SUSPENDED, reason, days?}` — days 1~365, 생략 = 영구 | RESOLVED |
| `UNSUSPEND` | USER | user `PATCH /status {status: ACTIVE, reason}` | DISMISSED |
| `DISMISS` | 둘 다 | 없음 | DISMISSED |

| 경우 | 응답 |
|---|---|
| 정상 — **대상 서비스 성공 뒤** 조치 1행 + 열린 신고 일괄 닫기 (한 트랜잭션) | **200** |
| 대상에 맞지 않는 조치 · `SUSPEND` 가 아닌데 `days` | 400 `REPORT_ACTION_NOT_ALLOWED` |
| `reason` 없음 · 255자 초과 · `days` 범위 밖 | 400 `INVALID_INPUT` |
| 대상 서비스 4xx (이미 정지 등) | 그 code 그대로 — 아무것도 기록하지 않음 |
| 대상 서비스 장애 | 502 `EXTERNAL_API_ERROR` — 아무것도 기록하지 않음, 신고는 OPEN 그대로(다시 처리) |

- 열린 신고가 없어도 처리할 수 있다(`closedReports: 0`) — 예: 정지 해제
- 대상 서비스가 같은 상태 재요청을 200 으로 받으므로(멱등) 두 번 눌러도 상태는 같고 조치 이력만 한 줄 더 남는다

## 5. 알림 센터 (NTF-08, 10/27 회의 추가 기능)

업무 이벤트와 다른 서비스 요청을 **받는 쪽별 알림**으로 바꿔 알림함(DynamoDB `sesac-gojigi-inbox`, 90일)에 쌓고, 연결된 화면에 SSE 로 바로 보냅니다. 파드가 여러 개여도 Redis Pub/Sub(`notify:{받는 쪽}`)로 모든 파드가 자기 연결에 전달합니다.

| 받는 쪽 (알림함 PK) | 누가 보나 |
|---|---|
| `USER#{userId}` | 그 회원 (구매자 · 판매자 · 관리자 모두) |
| `STORE#{storeId}` | 그 스토어 판매자 (`X-Store-Id`) — 이벤트에 `storeId` 만 있는 경우(정산 지급) |
| `ADMIN` | 관리자 전원 (시스템 알림 등) |

**이벤트 → 알림** (구독 큐 `sesac-gojigi-admin-{store|order|settlement}-events`)

| 이벤트 | 받는 쪽 | type · 제목 | 링크 |
|---|---|---|---|
| `OrderPaid` | 구매자 | `ORDER_PAID` 결제가 완료됐어요 — "상품 외 n건 · 금액" | `/mypage/orders/{orderNo}` |
| `OrderItemShipped` | 구매자 | `ORDER_SHIPPED` 배송이 시작됐어요 — "상품 · 택배사 송장" | 〃 |
| `OrderItemCancelled` · `OrderItemReturned` | 구매자 | `ORDER_CANCELLED` · `ORDER_RETURNED` — "상품 · 환불 금액" (order 발행 전 — ORD-11) | 〃 |
| `SettlementPaid` | 스토어 | `SETTLEMENT_PAID` 정산금이 지급됐어요 — "주차 · 지급액" | `/seller/settlements` |
| `StoreApproved` · `StoreApplicationRejected` · `StoreApplicationSupplementRequested` | 신청 회원 | `STORE_APPROVED` · `STORE_REJECTED` · `STORE_SUPPLEMENT` | `/seller` · `/seller/apply` |

같은 이벤트 재전달은 알림함 키(`{발생 시각 ms}#{eventId}`)가 같아 한 번만 쌓입니다.

### API (게이트웨이 `/api/notifications/**` → admin, 로그인한 누구나 자기 알림만)

| 메서드 · 경로 | 응답 |
|---|---|
| `GET /api/notifications?cursor=&size=20` | `{items[{id, type, title, body, link, read, createdAt}], nextCursor, unreadCount}` — 최신순, `size` 최대 50 |
| `GET /api/notifications/unread-count` | `{count}` |
| `PATCH /api/notifications/{id}/read` | 내 알림이 아니거나 없으면 **404** `NOTIFICATION_NOT_FOUND` |
| `PATCH /api/notifications/read-all` | `{count}` = 읽음으로 바꾼 수 |
| `GET /api/notifications/stream` | `text/event-stream` — `event: ready` → 새 알림마다 `event: notification`(data = 목록 항목 JSON), 25초마다 주석. 브라우저 `EventSource` 는 Authorization 헤더를 못 보내 화면은 fetch 기반 SSE 사용 |

### 내부 — `POST /internal/notifications` (클러스터 내부 전용)

```json
{ "userIds": [4], "storeIds": [1], "admins": false, "type": "CHAT_MESSAGE", "title": "새 메시지", "body": "안녕하세요", "link": "/mypage/chats/3", "sourceId": "chat-msg-9" }
```

- **202** `{recipients}` — 받는 쪽마다 쌓고 바로 보냄. `sourceId` 가 같으면 한 번만(재시도 멱등), 생략하면 매번 새 알림
- **400** `NOTIFICATION_RECIPIENT_REQUIRED`(받는 쪽 없음) · `INVALID_INPUT`(type · title 없음, 길이 초과)
