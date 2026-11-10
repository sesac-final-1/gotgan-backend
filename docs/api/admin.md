# admin-service API — 신고 · 조치 (10/26 B안) · 알림 센터 (10/27) · 장애 시연 (11/7)

- 담당: 구지훈. 다른 관리자 API(회원 · 정산 · 수수료 · 카테고리 · 대시보드 · 입점 · 스토어)는 하위 서비스 계약(user · settlement · product · store.md)을 그대로 전달하므로 여기에는 **admin 이 직접 갖는 신고 · 알림 센터 · 장애 시연만** 적는다
- 근거: ADR-0001 "변경 (10/26)" — 회원 신고 범위 복귀 · admin DB 예외(신고 · 조치 이력만), 노션 확인 요청 「신고 · 리뷰 블라인드 (B안)」
- 저장: admindb `report`(신고자 · 대상당 1건) · `moderation_action`(처리 1번 = 1행). 리뷰 블라인드 상태는 review, 회원 정지 상태는 user 가 주인

## 공통

| 항목 | 규칙 |
|---|---|
| 대상 `targetType` | `REVIEW` · `USER` |
| 신고 상태 | `OPEN`(처리 대기) → 관리자 조치 한 번에 대상의 열린 신고가 모두 `RESOLVED`(블라인드 · 정지) 또는 `DISMISSED`(해제 · 기각) |
| 대상 서비스 | 리뷰 조회 · 블라인드 = review `GET` · `PATCH /internal/reviews/{id}(/blind)` (서킷 `review`) — 회원 상세 "작성 리뷰" `GET /api/admin/users/{id}/reviews` 도 같은 서킷으로 review `GET /internal/reviews?userId=`(review.md 8-3) 를 그대로 전달, 회원 조회 · 정지 = user `GET` · `PATCH /internal/users/{id}(/status)` (서킷 `user`). 오류는 HTTP 상태 · code 그대로, 장애는 502 `EXTERNAL_API_ERROR` |
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

업무 이벤트와 다른 서비스 요청을 **받는 쪽별 알림**으로 바꿔 알림함(DynamoDB `sesac-gojigi-inbox`, TTL 90일 — 단 표를 매일 08:00 새로 만들어 실제로는 하루, 11/6)에 쌓고, 연결된 화면에 SSE 로 바로 보냅니다. 파드가 여러 개여도 Redis Pub/Sub(`notify:{받는 쪽}`)로 모든 파드가 자기 연결에 전달합니다.

| 받는 쪽 (알림함 PK) | 누가 보나 |
|---|---|
| `USER#{userId}` | 그 회원 (구매자 · 판매자 · 관리자 모두) |
| `STORE#{storeId}` | 그 스토어 판매자 (`X-Store-Id`) — 이벤트에 `storeId` 만 있는 경우(정산 지급) |
| `ADMIN` | 관리자 전원 (시스템 알림 등) |

**이벤트 → 알림** (구독 큐 `sesac-gojigi-admin-{store|order|settlement}-events`)

| 이벤트 | 받는 쪽 | type · 제목 | 링크 |
|---|---|---|---|
| `OrderPaid` | 구매자 | `ORDER_PAID` 결제가 완료됐어요 — "상품 외 n건 · 금액" | `/mypage/orders/{orderNo}` |
| `OrderPaid` (11/3, O-13) | 주문에 든 스토어마다 판매자(`STORE#`) | `ORDER_NEW` 새 주문이 들어왔어요 — "그 스토어 상품 외 n건 · 그 스토어 상품 금액 합" (`items[].storeId` 없는 옛 이벤트면 생략) | `/seller/orders` |
| `OrderClaimRequested` (11/5, Q-구-15) | 그 스토어 판매자(`STORE#`) | `CLAIM_REQUESTED` 반품 / 교환 요청이 들어왔어요 · 상품명 · 옵션 · `/seller/claims` |
| `OrderItemShipped` | 구매자 | `ORDER_SHIPPED` 배송이 시작됐어요 — "상품 · 택배사 송장" | 〃 |
| `OrderItemCancelled` · `OrderItemReturned` | 구매자 | `ORDER_CANCELLED` 취소가 완료됐어요 · `ORDER_RETURNED` 반품이 완료됐어요 — "상품 · 환불 금액" | 〃 |
| `SettlementPaid` | 스토어 | `SETTLEMENT_PAID` 정산금이 지급됐어요 — "주차 · 지급액" | `/seller/settlements` |
| `StoreApproved` · `StoreApplicationRejected` · `StoreApplicationSupplementRequested` | 신청 회원 | `STORE_APPROVED` · `STORE_REJECTED` · `STORE_SUPPLEMENT` | `/seller` · `/seller/apply` |

같은 이벤트 재전달은 알림함 키(`{발생 시각 ms}#{eventId}`)가 같아 한 번만 쌓입니다.

**다른 서비스 요청 → 알림** (아래 내부 API)

| 호출 서비스 | 받는 쪽 | type | 링크 |
|---|---|---|---|
| chat-service — 상대가 그 방을 보고 있지 않을 때 새 채팅 메시지 (`chat.md` 7절, `sourceId = chat-msg-{messageId}`) | 구매자 · 스토어 · 관리자 전원 | `CHAT_MESSAGE` | `/mypage/chats/{roomId}` · `/seller/chats/{roomId}` · `/admin/chats/{roomId}` |

알림 종류(`type`): `ORDER_PAID` · `ORDER_NEW` · `ORDER_SHIPPED` · `ORDER_CANCELLED` · `ORDER_RETURNED` · `CLAIM_REQUESTED` · `SETTLEMENT_PAID` · `STORE_APPROVED` · `STORE_REJECTED` · `STORE_SUPPLEMENT` · `CHAT_MESSAGE`. 화면은 `CHAT_MESSAGE` 를 받으면 채팅 목록 · 안 읽은 수 배지도, `ORDER_NEW` 를 받으면 판매자 주문 목록 · 대시보드도 갱신합니다.

### API (게이트웨이 `/api/notifications/**` → admin, 로그인한 누구나 자기 알림만)

| 메서드 · 경로 | 응답 |
|---|---|
| `GET /api/notifications?cursor=&size=20` | `{items[{id, type, title, body, link, read, createdAt}], nextCursor, unreadCount}` — 최신순, `size` 최대 50 |
| `GET /api/notifications/unread-count` | `{count}` — 받는 쪽(USER# · STORE#)마다 100까지만 셈(화면은 99+) |
| `PATCH /api/notifications/{id}/read` | 내 알림이 아니거나 없으면 **404** `NOTIFICATION_NOT_FOUND` |
| `PATCH /api/notifications/read-all` | `{count}` = 읽음으로 바꾼 수 |
| `GET /api/notifications/stream` | `text/event-stream` — `event: ready` → 새 알림마다 `event: notification`(data = 목록 항목 JSON), 25초마다 주석. 브라우저 `EventSource` 는 Authorization 헤더를 못 보내 화면은 fetch 기반 SSE 사용 |

### 내부 — `POST /internal/notifications` (클러스터 내부 전용)

```json
{ "userIds": [4], "storeIds": [1], "admins": false, "type": "CHAT_MESSAGE", "title": "새 메시지", "body": "안녕하세요", "link": "/mypage/chats/3", "sourceId": "chat-msg-9" }
```

- **202** `{recipients}` — 받는 쪽마다 쌓고 바로 보냄. `sourceId` 가 같으면 한 번만(재시도 멱등), 생략하면 매번 새 알림
- **400** `NOTIFICATION_RECIPIENT_REQUIRED`(받는 쪽 없음) · `INVALID_INPUT`(type · title 없음, 길이 초과)

## 6. 관리자 중계 API — 경로 목록 (11/3)

> admin 은 관리자 화면의 입구(BFF). 아래 경로는 **하위 서비스 내부 API 를 그대로 중계**하므로 요청 · 응답 · 오류 계약은 각 서비스 문서가 정본이다.
> 공통: 게이트웨이가 ADMIN 역할 확인 · 관리자 X-User-Id 를 그대로 전달, 동기 호출은 서킷 브레이커 · GET 재시도(1홉 규칙), 하위 서비스 장애는 502.

| 영역 | 경로 (전부 ADMIN) | 하위 서비스 (정본 문서) |
|---|---|---|
| 대시보드 | `GET /api/admin/dashboard` — 영역별 병렬 조합 · 일부 실패해도 200 + `unavailable[]` | settlement · order · product 통계 |
| 입점 심사 | `GET /api/admin/applications` · `/{id}` · `PATCH /{id}/approve` · `/reject` · `/supplement` | store (store.md 4절) |
| 스토어 | `GET /api/admin/stores` · `/{storeId}` · `PATCH /{storeId}/status` | store (store.md 1절 확장) |
| 스토어 상품 · 차단 | `GET /api/admin/stores/{storeId}/products` · `PATCH /api/admin/products/{productId}/block` | product (product.md 6절) |
| 카테고리 | `GET · POST /api/admin/categories` · `PUT /{categoryId}` | product 7절 + settlement 수수료(상위 생성 · 활성화) |
| 수수료 | `GET · PUT /api/admin/commissions` | settlement (settlement.md 2절) |
| 정산 | `GET /api/admin/settlements` · `/{id}` · `POST /aggregate` · `/pay` · `PATCH /{id}/hold` · `GET /api/admin/stores/{storeId}/settlements` — `aggregate` · `pay` 만 settlement 호출 읽기 28초 · gateway 라우트 `admin-settlement-batch` 30초(11/6, 5,000건 집계 5.6 ~ 11.6초), 나머지는 3초 · gateway 10초 | settlement (settlement.md 3절) |
| 회원 | `GET /api/admin/users` · `/{userId}` · `/{userId}/points` · `/{userId}/reviews` · `PATCH /{userId}/status` | user 내부 API · review 작성 리뷰 |
| 신고 · 알림 | 1 ~ 5절 | admin 자체 (admindb · DynamoDB) |
| 장애 시연 | 7절 | admin 자체 (쿠버네티스 API · admindb) |

## 7. 장애 시연 대시보드 — F-1 (11/7)

> 관리자가 서비스 파드를 내리고 상태 · 복구를 한 화면에서 본다(슬로건 "하나가 멈춰도 곳간은 열려 있다"). 근거: 노션 협업 요청 「F-1 장애 시연 대시보드」 4장 계약 · 11/6 QA 장애 영향표.
> 전부 `ADMIN` — 게이트웨이 `/api/admin/**` 확인에 더해 admin 도 `X-User-Roles` 에 ADMIN 이 없으면 403 `FORBIDDEN`.

| 항목 | 규칙 |
|---|---|
| 데이터 | 쿠버네티스 API(in-cluster ServiceAccount 토큰 · ca.crt) — `sesac-gojigi` 네임스페이스 Deployment · StatefulSet `spec.replicas` + 파드(`app` 라벨). 권한 = gotgan-config `chaos-rbac.yaml`(pods get · list · delete, deployments · statefulsets get · list) |
| 스위치 | `app.chaos.enabled`(env `APP_CHAOS_ENABLED`, 기본 `false`) — 꺼져 있으면 **내리기만** 403, 상태 · 타임라인은 조회 가능 |
| 내리기 방식 | 파드 **즉시 삭제**(`gracePeriodSeconds=0`)만 — Deployment 가 새 파드를 띄움 · ArgoCD selfHeal 과 충돌 없음. 레플리카를 0 으로 줄이지 않는다. 기본 유예로 지우면 옛 파드가 preStop 10초 동안 응답해 실패 구간이 8초 안팎뿐(11/10) |
| 기록 | admindb `chaos_event` — 내리기 1번 = 1행(서비스 · 범위 · 사유 · 지운 파드 · 관리자 · 시각 · 복구 시각) |

### 서비스 · 상태

| 서비스 | 내리기 |
|---|---|
| `order-service` · `product-service` · `payment-service` · `store-service` · `user-service` · `review-service` · `settlement-service` · `chat-service` | ✅ (`killable: true`) |
| `gateway-service` · `admin-service` · `keycloak` · `mysql` · `redis` | ❌ 입구 · 이 화면 · 로그인 · 데이터 (`killable: false`, 상태만) |

`ready` = 지워지는 중이 아닌 Ready 파드 수, `desired` = `spec.replicas`.

| `state` | 조건 |
|---|---|
| `UP` | `ready ≥ desired` |
| `DOWN` | `ready = 0` — 새 파드가 뜨는 중이어도 응답을 못 하므로 DOWN |
| `RECOVERING` | `0 < ready < desired` 이고 마지막 내리기가 아직 복구 전 |
| `DEGRADED` | `0 < ready < desired` 이고 내리기와 무관(자연 장애 · 재시작) |

### 사용자 기능 `features` (서버 계산, 11/6 QA 실측)

| `key` | `label` | 원인 서비스 |
|---|---|---|
| `BROWSE` | 둘러보기 · 상품 상세 | product |
| `STORE` | 스토어 | store |
| `REVIEW` | 리뷰 · 문의 | review |
| `CART_ORDER` | 장바구니 · 주문 | order · product · store (장바구니가 상품 · 스토어 배송 정책을 읽음) |
| `PAYMENT` | 결제 | order · payment |
| `CHAT` | 채팅 | chat |
| `SETTLEMENT` | 판매자 정산 | settlement |
| `MYPAGE` | 회원 · 마이페이지 | user |

원인 중 하나라도 `DOWN` → `DOWN`, `DEGRADED` · `RECOVERING` 만 → `DEGRADED`, 아니면 `UP`. `causes` = `UP` 이 아닌 원인 서비스.

### 7-1. 상태 — `GET /api/admin/system/status` (2초 폴링)

```json
{ "chaosEnabled": true, "serverTime": "2026-11-09T14:03:12+09:00",
  "services": [{ "name": "order-service", "killable": true, "desired": 2, "ready": 0, "state": "DOWN",
                 "pods": [{ "name": "order-service-…", "node": "ip-10-0-11-…", "phase": "Pending", "ready": false,
                            "restarts": 0, "ageSeconds": 3, "terminating": false }] }],
  "features": [{ "key": "CART_ORDER", "label": "장바구니 · 주문", "state": "DOWN", "causes": ["order-service"] }] }
```

- `services` 는 위 표 순서(13개), `pods` 는 이름순 · 지워지는 중(`terminating: true`)인 파드 포함
- 복구 시각은 이 조회가 "내린 뒤 `ready ≥ desired`" 를 처음 본 때 기록(별도 감시 없음 — 정확도 = 폴링 주기)
- **503** `SYSTEM_CLUSTER_UNAVAILABLE` — 쿠버네티스 조회 실패(클러스터 밖 · 권한 · 타임아웃). 화면은 마지막 값 유지 · "연결 끊김"

### 7-2. 내리기 — `POST /api/admin/system/services/{name}/kill`

`{ "scope": "ONE" | "ALL", "reason"?: "≤255자" }` → **202** `{ "eventId": 12, "deletedPods": ["order-service-…"] }`

- `ONE` = Ready 파드 1개(이름순 첫 번째), `ALL` = 지워지는 중이 아닌 파드 전부. 파드 1개 서비스는 둘이 같음
- 일부만 지우고 실패하면 지운 파드만 기록하고 503
- 검사 순서: 꺼짐 → 허용 목록 → 1분 제한 → 복구 중 → Ready 파드

| 응답 | 코드 |
|---|---|
| 403 | `SYSTEM_CHAOS_DISABLED` — 스위치 꺼짐 |
| 400 | `SYSTEM_SERVICE_NOT_KILLABLE` — 허용 목록 밖(없는 이름 포함) · `INVALID_INPUT` — scope 없음 |
| 409 | `SYSTEM_KILL_COOLDOWN` — 그 서비스를 1분 안에 내렸거나 복구 중(`RECOVERING`) |
| 409 | `SYSTEM_NO_READY_POD` — 지울 파드가 없음(ONE 인데 Ready 0개 등) |
| 503 | `SYSTEM_CLUSTER_UNAVAILABLE` |

### 7-3. 타임라인 — `GET /api/admin/system/events`

최신순 20건: `[{ "eventId", "service", "scope", "reason", "deletedPods": [], "actorId", "killedAt", "recoveredAt", "recoverySeconds" }]` — 복구 전이면 `recoveredAt` · `recoverySeconds` 가 `null`.

### 7-4. 지표 — `GET /api/admin/system/metrics` (15초 폴링)

```json
{ "services": [{ "name": "order-service", "requestsPerSecond": 2.0, "errorRate": 0.25 },
               { "name": "gateway-service", "requestsPerSecond": null, "errorRate": null }] }
```

- 출처: Prometheus `/api/v1/query`(`app.prometheus.url`, env `PROMETHEUS_URL`, 기본 `http://prometheus.monitoring:9090`) — 연결 · 읽기 제한 2초
- 지표: gateway `spring_cloud_gateway_requests_seconds_count` 1분 rate — 서비스를 전부 내리면 그 서비스는 자기 5xx 를 못 세므로 gateway 에서 센다(11/6 실측: 내린 서비스는 502)
  - 요청량 `sum by (routeId)(rate(…[1m]))`, 오류 `sum by (routeId)(rate(…{httpStatusCode=~"5.."}[1m]))`
  - 라우트를 서비스로 묶어 rate 를 더한 뒤 `errorRate = 5xx ÷ 전체`(0 ~ 1, 소수 4자리) · `requestsPerSecond` 소수 2자리
- `services` 는 7-1 과 같은 13개 · 같은 순서. 스위치가 꺼져 있어도 조회 가능
- `null` 규칙: 요청이 0 이면 `errorRate` 만 `null`(0% 가 아님) · 라우트가 없는 서비스(gateway · keycloak · mysql · redis)는 둘 다 `null` · **Prometheus 실패면 503 이 아니라 200 에 전부 `null`**(부분 응답, 화면은 `-`)

| gateway 라우트 id | 서비스 |
|---|---|
| `user-auth-strict` · `user-auth` · `user-service` | user-service |
| `admin-notification-stream` · `admin-settlement-batch` · `admin-service` | admin-service |
| `chat-service` · `chat-websocket` | chat-service |
| `store-service` · `product-service` · `order-service` · `settlement-service` · `payment-service` · `review-service` | 같은 이름 |
| `keycloak` | 제외 |

> 라우트를 추가 · 이름 변경하면 `SystemCatalog.ROUTE_SERVICES` 도 고친다 — 표에 없는 id 는 지표에서 빠진다.
