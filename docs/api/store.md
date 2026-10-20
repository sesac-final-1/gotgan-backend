# store-service API 계약 (설계 — 10/20)

담당 박지연. 입점 신청(`/api/seller/apply/**`) · 판매자 스토어(`/api/seller/store/**` · `/api/seller/dashboard`) · 공개 스토어(`/api/stores/**`) · 다른 서비스(user · admin · order)가 부르는 `/internal/**` 의 **계약**입니다. 엔드포인트마다 PR 하나로 구현하면서 해당 절을 이 문서에 추가합니다. 코드가 정본(springdoc)이고, 이 문서는 호출하는 쪽이 지킬 규칙 설명으로 남깁니다.
근거: 스펙 STR-01~14, storedb V1 · V2, 노션 "10/20 회원 탈퇴 — 판매자는 스토어 폐점 후 탈퇴" 답변.

## 공통

| 항목 | 값 |
|---|---|
| 주소 | 화면: 게이트웨이 `http://gotgan-api.duckdns.org` (HTTPS 전환 예정) — `/api/stores/**` · `/api/seller/apply/**` · `/api/seller/store/**` · `/api/seller/dashboard`<br>내부: `STORE_SERVICE_URI=http://store-service:8082` (ConfigMap) — `/internal/**` 는 게이트웨이 라우트 없음, 클러스터 내부 전용 |
| 응답 | `ApiResponse<T>` 봉투 (컨벤션 2-4). 오류는 `code` · `message`, `data: null` |
| 금액 | `long`, 원 단위 |
| 시각 | ISO-8601 오프셋 포함 (Asia/Seoul) |
| 인가 | 공개 GET: 로그인 없음 / 입점 신청: BUYER / 판매자: SELLER + `X-Store-Id` 헤더의 스토어만 / 내부: 헤더 없음 |

### 스토어 상태 `status` (10/20 결정)

| 값 | 뜻 | 거는 쪽 → 푸는 쪽 |
|---|---|---|
| `ACTIVE` | 운영 중 — 승인 직후 상태 | — |
| `DORMANT` | 휴면 | 판매자 → 판매자 |
| `SUSPENDED` | 정지 | 관리자 → 관리자 (사유 필수) |
| `CLOSED` | 폐점 — **되돌릴 수 없는 마지막 상태**. 판매자 탈퇴 조건 | 판매자 (폐점 API 는 다음 PR) |

- 폐점은 `deleted_at` 이 아니라 `status` 로 표현합니다. 폐점한 스토어도 주문 · 정산 내역과 내부 API 에서 계속 조회되어야 하기 때문입니다. `deleted_at` 은 컨벤션 3-5 소프트 삭제 자리로만 두고, 채워진 스토어는 모든 조회에서 없는 것으로 봅니다.
- 상태 값은 늘어날 수 있습니다. 호출하는 쪽은 `ACTIVE` 인지 아닌지(또는 필요한 값인지)로 판단하고, 모르는 값이 와도 실패하지 않게 해 주세요.

---

## 1. 스토어 상세 (내부) — `GET /internal/stores/{storeId}` (STR-14 · 회원 탈퇴)

user-service 판매자 탈퇴(`DELETE /api/users/me`)가 스토어 폐점 여부를 확인할 때, admin-service 스토어 관리가 부릅니다. DB 조회만 하므로 재시도 가능(GET).

```json
{
  "code": "SUCCESS",
  "message": "스토어를 조회했습니다.",
  "data": { "storeId": 1, "userId": 2, "storeName": "곳간상회", "status": "ACTIVE" }
}
```

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| 스토어 있음 (상태 무관, `CLOSED` 포함) | 조회 | **200** `{storeId, userId, storeName, status}` | user: `status == CLOSED` 일 때만 탈퇴 진행. `userId` 가 요청자와 다르면 탈퇴 거부 권장 |
| 없는 스토어 · 소프트 삭제된 스토어 | — | **404** `STORE_NOT_FOUND` | user: 탈퇴 거부(안전한 쪽). 서킷 실패로 세지 않음 |
| `storeId` 가 숫자가 아님 | — | **400** `INVALID_INPUT` | 호출 코드 버그 |
| store 장애 · 타임아웃 | — | 연결 실패 · 5xx | user: 서킷 `store` 로 집계, 탈퇴 차단 |

- 응답 필드는 **추가만** 합니다(admin 스토어 상세에서 필요한 필드를 붙일 예정). 호출하는 쪽은 모르는 필드를 무시해 주세요.

## 2. 오류 코드 (`docs/api/error-codes.md`)

도메인 코드는 store-service 안 도메인별 enum(`{domain}/exception/{Domain}ErrorCode`), 공통 코드는 `libs/common-web` 의 `CommonErrorCode` 입니다.

| 코드 | HTTP | enum | 언제 |
|---|---|---|---|
| `STORE_NOT_FOUND` | 404 | `StoreErrorCode` | 없거나 소프트 삭제된 스토어 (1절) |
| `INVALID_INPUT` (공통) | 400 | `CommonErrorCode` | 경로 · 요청 형식 오류 |

호출하는 서비스는 404 · 400 을 자기 도메인 코드로 바꾸거나 그대로 전달하고, **서킷 실패로 세지 않습니다**(`ignore-exceptions` = `BusinessException`).

## 3. 호출 쪽 준비 사항

**user (이경석)**
- `MockStoreClient` 를 `@HttpExchange` 클라이언트(`GET /internal/stores/{storeId}`)로 교체. 서킷 브레이커 인스턴스 이름은 **`store`**, 재시도 가능(GET).
- user-service `StoreStatus` enum 값(ACTIVE · DORMANT · SUSPENDED · CLOSED)은 이 문서와 같습니다. 바꿀 것 없음.
- 지금은 폐점 API 가 없어 `CLOSED` 스토어가 생기지 않습니다 → 판매자 탈퇴는 폐점 API PR 전까지 계속 409 `USER_SELLER_STORE_NOT_CLOSED`.

**admin (구지훈)**: 스토어 관리(STR-14) 상세에서 1절 사용 가능. 목록 · 상태 이력 필드는 해당 PR 에서 추가.

## 10/20 설계 정리

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 폐점 표현 | `status = CLOSED` 추가 (`deleted_at` 아님). `status` 는 VARCHAR 라 DDL 변경 없음 |
| 2 | 내부 상세 응답 | `{storeId, userId, storeName, status}` 로 시작, 필드는 추가만 |
| 3 | 소프트 삭제된 스토어 | 내부 API 도 404 (컨벤션 3-5 활성 조회 조건). 폐점은 삭제가 아니라서 200 |
| 4 | 판매자 폐점 API · 조건 | 다음 PR. 진행 중 주문(order) · 미정산(settlement) 확인 방법은 담당자와 협의 후 결정. 그때 `events.md` 의 `StoreStatusChanged.status` 값 목록에 `CLOSED` 추가 |
