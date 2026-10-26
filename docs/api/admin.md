# admin-service API — 신고 · 조치 (10/26 B안)

- 담당: 구지훈. 다른 관리자 API(회원 · 정산 · 수수료 · 카테고리 · 대시보드 · 입점 · 스토어)는 하위 서비스 계약(user · settlement · product · store.md)을 그대로 전달하므로 여기에는 **admin 이 직접 갖는 신고만** 적는다
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
