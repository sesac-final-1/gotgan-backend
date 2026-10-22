# store-service API 계약 (설계 — 10/20 · 10/22)

담당 박지연. 입점 신청(`/api/seller/apply/**`) · 판매자 스토어(`/api/seller/store/**` · `/api/seller/dashboard`) · 공개 스토어(`/api/stores/**`) · 다른 서비스(user · admin · order)가 부르는 `/internal/**` 의 **계약**입니다. 엔드포인트마다 PR 하나로 구현하면서 해당 절을 이 문서에 추가합니다. 코드가 정본(springdoc)이고, 이 문서는 호출하는 쪽이 지킬 규칙 설명으로 남깁니다.
근거: 스펙 STR-01~14, storedb V1 · V2, 노션 "10/20 회원 탈퇴 — 판매자는 스토어 폐점 후 탈퇴" 답변 · "10/21 판매자 전환 — 입점 신청 · 승인은 store 쪽 API" 요청.

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

## 2. 입점 신청 — `POST /api/seller/apply` (STR-01 · 02 · 11)

구매자(BUYER)가 입점 신청 화면(`/seller/apply`, 4단계 폼)을 제출합니다. 신청자는 게이트웨이가 넣는 `X-User-Id` 이고, 승인 때 `StoreApproved.userId` 로 그대로 나갑니다. 서류는 먼저 presign(`POST /api/seller/apply/documents/presign`, 다음 PR)으로 S3 에 올리고 받은 키를 보냅니다.

```json
{
  "bizType": "INDIVIDUAL",
  "companyName": "곳간상회",
  "ownerName": "홍길동",
  "bizRegNo": "000-00-00001",
  "ecommerceRegNo": "2026-서울성동-0000",
  "managerPhone": "010-0000-1234",
  "managerEmail": "seller@example.com",
  "businessAddress": "서울시 성동구 성수이로 00",

  "storeName": "곳간상회",
  "slug": "gotgan-store",
  "categoryId": 1,
  "intro": "<p>리넨 의류를 만듭니다.</p>",
  "representativeProducts": [
    { "name": "리넨 셔츠", "subCategory": "셔츠", "price": 39000 },
    { "name": "리넨 팬츠", "subCategory": "팬츠", "price": 45000 }
  ],
  "expectedMonthlySales": "FROM_5M_TO_20M",
  "salesChannel": "https://instagram.com/gotgan.store",

  "documents": [
    { "docType": "BUSINESS_REG", "key": "applications/4/3f2a0c5e-....pdf", "originalName": "사업자등록증.pdf" },
    { "docType": "ECOMMERCE_REG", "key": "applications/4/9b1c....pdf", "originalName": "통신판매업신고증.pdf" },
    { "docType": "BANK_COPY", "key": "applications/4/c4d8....jpg", "originalName": "통장사본.jpg" },
    { "docType": "PRODUCT_PHOTO", "key": "applications/4/0a11....jpg", "originalName": "상품1.jpg" },
    { "docType": "PRODUCT_PHOTO", "key": "applications/4/0a12....jpg", "originalName": "상품2.jpg" },
    { "docType": "PRODUCT_PHOTO", "key": "applications/4/0a13....png", "originalName": "상품3.png" }
  ],
  "settlementAccount": { "bank": "국민", "number": "000000-00-000000", "holder": "홍길동" },
  "agreements": { "sellerTerms": true, "electronicFinance": true, "privacy": true, "marketing": false }
}
```

**입력 규칙** — 어기면 400 `INVALID_INPUT`(서류만 `APPLY_DOCUMENT_INVALID`). 길이는 storedb 컬럼 크기와 같습니다.

| 필드 | 규칙 |
|---|---|
| `bizType` | `INDIVIDUAL` · `CORPORATE`. 서류 종류는 같고 심사 기준만 다름 |
| `companyName` · `ownerName` | 필수, 100자 · 50자 이하 |
| `bizRegNo` | `000-00-00000` 형식 (하이픈 포함) |
| `ecommerceRegNo` | 필수, 50자 이하 |
| `managerPhone` | `010-0000-0000` 형식. 승인 때 스토어 고객 문의 연락처(`cs_phone`)로 복사 |
| `managerEmail` | 이메일 형식, 100자 이하. **심사 결과 메일 수신 주소 = `StoreApproved.sellerEmail`** |
| `businessAddress` | 필수, 255자 이하 |
| `storeName` | 필수, 50자 이하 |
| `slug` | 3~30자, 영소문자 · 숫자 · `-`, `-` 로 시작 · 끝 금지, 예약어(`admin` · `seller` · `new` · `api` · `me`) 금지. 승인 후 변경 불가 |
| `categoryId` | 필수, 양수. 상위 카테고리 존재 · 사용 여부는 **store 가 확인하지 않음**(심사 참고용 — 설계 정리 4) |
| `intro` | 선택. HTML 은 sanitizer 로 정화해 저장 |
| `representativeProducts` | **2~10개**. `name` 필수 · `subCategory`(세부 분류 이름) 필수 · `price` 1 이상. 심사 화면 표시용이라 받은 모양 그대로 JSON 컬럼에 저장 |
| `expectedMonthlySales` | 선택. `UNDER_5M` · `FROM_5M_TO_20M` · `FROM_20M_TO_100M` · `OVER_100M` |
| `salesChannel` | 선택, 255자 이하 |
| `settlementAccount` | `bank` 30자 · `number` 30자 · `holder` 50자, 모두 필수. 승인 때 `StoreApproved.settlementAccount` 로 그대로 |
| `agreements` | 필수 약관 `sellerTerms` · `electronicFinance` · `privacy` 모두 `true`. `marketing` 은 선택(없으면 `false`). 동의 시각은 서버가 기록 |

**서류 `documents`** — 위반은 모두 400 `APPLY_DOCUMENT_INVALID`, 메시지에 어느 서류가 왜 틀렸는지 적습니다.

| 항목 | 규칙 |
|---|---|
| 종류 · 개수 | `BUSINESS_REG` · `ECOMMERCE_REG` · `BANK_COPY` 각 1개, `PRODUCT_PHOTO` 3~10개. 업종별 추가 서류 없음 |
| 키 | `applications/{X-User-Id}/{UUID}.{확장자}` — presign 이 만든 **내 키만**. 목록 안 중복 금지 |
| 파일 | S3 에서 실제 파일을 확인(`HeadObject`)해 형식 · 크기를 저장. PDF · JPG · PNG(상품 사진은 JPG · PNG만), 10MB 이하, 올라가지 않은 키는 거절 |
| 발급일 3개월 이내 | 시스템이 검사하지 않음 — 관리자 심사(STR-10) |

**처리 · 응답** — 아래 순서로 확인하고, 처음 걸린 것을 돌려줍니다.

| 경우 | 처리 | 응답 | 화면 권장 처리 |
|---|---|---|---|
| `X-User-Id` 없음 | — | **401** `UNAUTHORIZED` | 로그인으로 |
| 형식 오류 (위 입력 규칙) | — | **400** `INVALID_INPUT` | 해당 입력 칸 안내 |
| 이미 스토어가 있음 — **폐점(`CLOSED`) 포함** | — | **409** `APPLY_STORE_ALREADY_EXISTS` | 판매자센터로 (1인 1스토어) |
| 진행 중 신청(`PENDING` · `SUPPLEMENT`)이 있음 | — | **409** `APPLY_IN_PROGRESS` | 내 신청 상태 화면으로 |
| 가장 최근 반려 후 30일이 안 지남 | — | **409** `APPLY_REAPPLY_RESTRICTED` | 메시지의 재신청 가능일 표시 |
| `slug` 가 스토어(폐점 포함) · 진행 중 신청과 겹침 | — | **409** `APPLY_SLUG_DUPLICATED` | 스토어 주소 칸 재입력 |
| `storeName` 이 스토어(폐점 포함) · 진행 중 신청과 겹침 (공백 · 대소문자 무시) | — | **409** `APPLY_STORE_NAME_DUPLICATED` | 스토어명 칸 재입력 |
| 서류 규칙 위반 | — | **400** `APPLY_DOCUMENT_INVALID` | 메시지대로 서류 다시 올리기 |
| S3 확인 실패 (장애 · 타임아웃) | 저장 안 함 | **502** `EXTERNAL_API_ERROR` | 잠시 후 다시 제출 |
| 정상 | 신청서 `PENDING` + 서류 행 + 이력(`NULL → PENDING`, `APPLICANT`)을 한 트랜잭션으로 저장 | **201** 아래 응답 | 접수 완료 화면 |

```json
{
  "code": "SUCCESS",
  "message": "입점 신청을 접수했습니다.",
  "data": {
    "applicationId": 27,
    "applicationNo": "AP-2610-027",
    "status": "PENDING",
    "submittedAt": "2026-10-22T14:05:11+09:00"
  }
}
```

- **접수번호 `applicationNo`** = `AP-{제출 연월 yyMM}-{applicationId, 3자리 이상}`. 저장하지 않고 응답마다 계산하는 **표시 전용** 값입니다. API 경로 · 식별에는 숫자 `applicationId` 를 씁니다. 번호는 달마다 001 부터 다시 시작하지 않습니다.
- 진행 중 확인은 서비스 검증이라, 동시에 두 번 제출하면 2건이 생길 수 있습니다(제출 버튼 중복 클릭 방지로 줄임). 스토어는 승인 때 `uk_store_user_id` 가 하나만 허용하므로 나머지는 관리자가 반려합니다. 신청끼리 slug · 스토어명이 겹친 경우도 승인 때 `uk_store_slug` · `uk_store_store_name` 이 막고, 관리자가 보완요청으로 변경을 안내합니다(STR-04).
- 이 API 는 이벤트를 발행하지 않습니다. `StoreApproved` 는 승인 API(관리자 `/internal/applications/{id}/approve`, 다음 PR)에서 발행합니다.

## 3. 오류 코드 (`docs/api/error-codes.md`)

도메인 코드는 store-service 안 도메인별 enum(`{domain}/exception/{Domain}ErrorCode`), 공통 코드는 `libs/common-web` 의 `CommonErrorCode` 입니다.

| 코드 | HTTP | enum | 언제 |
|---|---|---|---|
| `STORE_NOT_FOUND` | 404 | `StoreErrorCode` | 없거나 소프트 삭제된 스토어 (1절) |
| `APPLY_STORE_ALREADY_EXISTS` | 409 | `ApplyErrorCode` | 신청자에게 이미 스토어가 있음 — 폐점 포함 (2절) |
| `APPLY_IN_PROGRESS` | 409 | `ApplyErrorCode` | 심사대기 · 보완요청 중인 신청이 있음 (2절) |
| `APPLY_REAPPLY_RESTRICTED` | 409 | `ApplyErrorCode` | 반려 후 30일 안에 다시 신청 (2절, STR-11) |
| `APPLY_SLUG_DUPLICATED` | 409 | `ApplyErrorCode` | 스토어 주소가 스토어 · 진행 중 신청과 겹침 (2절) |
| `APPLY_STORE_NAME_DUPLICATED` | 409 | `ApplyErrorCode` | 스토어명이 스토어 · 진행 중 신청과 겹침 (2절) |
| `APPLY_DOCUMENT_INVALID` | 400 | `ApplyErrorCode` | 서류 종류 · 개수 · 형식 · 크기 · 키 위반, S3 에 없는 파일 (2절) |
| `INVALID_INPUT` (공통) | 400 | `CommonErrorCode` | 경로 · 요청 형식 오류 |
| `UNAUTHORIZED` (공통) | 401 | `CommonErrorCode` | 사용자 헤더 없음 (2절) |
| `EXTERNAL_API_ERROR` (공통) | 502 | `CommonErrorCode` | S3 확인 실패 (2절) |

호출하는 서비스는 404 · 400 을 자기 도메인 코드로 바꾸거나 그대로 전달하고, **서킷 실패로 세지 않습니다**(`ignore-exceptions` = `BusinessException`).

## 4. 호출 쪽 준비 사항

**user (이경석)**
- `MockStoreClient` 를 `@HttpExchange` 클라이언트(`GET /internal/stores/{storeId}`)로 교체. 서킷 브레이커 인스턴스 이름은 **`store`**, 재시도 가능(GET).
- user-service `StoreStatus` enum 값(ACTIVE · DORMANT · SUSPENDED · CLOSED)은 이 문서와 같습니다. 바꿀 것 없음.
- 지금은 폐점 API 가 없어 `CLOSED` 스토어가 생기지 않습니다 → 판매자 탈퇴는 폐점 API PR 전까지 계속 409 `USER_SELLER_STORE_NOT_CLOSED`.

**admin (구지훈)**: 스토어 관리(STR-14) 상세에서 1절 사용 가능. 목록 · 상태 이력 필드는 해당 PR 에서 추가.
- 입점 심사(STR-03 · 10): 업종별 추가 서류가 없어져(10/22) 체크리스트의 인증서 항목 · 보완요청 예시 "KC 인증서 제출" · 카테고리별 추가 서류 표시는 빼 주세요. 심사 목록 · 상세 응답은 `/internal/applications` PR 에서 이 문서에 추가합니다.

**user (이경석)**: `StoreApproved` 는 승인 API PR 부터 발행됩니다(2절은 발행 없음). 신청 단계에서 기존 스토어 회원을 409 `APPLY_STORE_ALREADY_EXISTS` 로 막습니다(요청 4번).

**frontend (박지연 — `features/store`)**: 4단계 폼 값을 2절 요청 모양으로 매핑. "모두 동의"는 필수 3개만 켜고 마케팅은 따로 체크 · 카테고리별 추가 서류 안내 제거 · 접수 완료 화면의 접수번호는 응답 `applicationNo`.

## 10/20 설계 정리

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 폐점 표현 | `status = CLOSED` 추가 (`deleted_at` 아님). `status` 는 VARCHAR 라 DDL 변경 없음 |
| 2 | 내부 상세 응답 | `{storeId, userId, storeName, status}` 로 시작, 필드는 추가만 |
| 3 | 소프트 삭제된 스토어 | 내부 API 도 404 (컨벤션 3-5 활성 조회 조건). 폐점은 삭제가 아니라서 200 |
| 4 | 판매자 폐점 API · 조건 | 다음 PR. 진행 중 주문(order) · 미정산(settlement) 확인 방법은 담당자와 협의 후 결정. 그때 `events.md` 의 `StoreStatusChanged.status` 값 목록에 `CLOSED` 추가 |

## 10/22 설계 정리 (입점 신청)

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 대표 상품 개수 | 2~10개 (화면 기준으로 스펙 STR-01 을 3 → 2 로 수정). V1 컬럼 주석의 "3개 이상"은 머지된 파일이라 그대로 둠 |
| 2 | 요청 필드 이름 | storedb 컬럼명의 camelCase. 정산 계좌만 `StoreApproved` 와 같은 `settlementAccount{bank, number, holder}` 객체 |
| 3 | 대표 상품 세부 분류 | id 가 아니라 **이름**(`subCategory`)으로 받아 그대로 저장 — 심사 표시 전용이라 product 조회 없이 보여 주기 위해 |
| 4 | 카테고리 검증 | 형식만(`@Positive`). product 호출은 하지 않음 — `category_id` 는 심사 참고용이고 정산 · 이벤트에 쓰이지 않음. **`category_id` 가 심사 밖(상품 등록 범위 · 이벤트 · 구매자 화면)에 쓰이게 되면** product 확인을 붙이되 장애 때는 통과(fail-open) |
| 5 | 서류 | 업종과 무관하게 4종(`CATEGORY_CERT` 는 쓰지 않음 — V1 주석에만 남음). 상품 사진 3~10장 · JPG/PNG. 형식 · 크기는 클라이언트 값이 아니라 S3 `HeadObject` 로 확인해 저장 |
| 6 | 접수번호 | 컬럼 없이 `AP-{yyMM}-{id}` 계산 (V3 없음, 동시성 문제 없음). 달마다 001 부터 시작하지 않는 것은 받아들임 |
| 7 | 약관 | 필수 3개 + 선택 1개를 객체로 받고, 저장은 `terms_agreed_at`(서버 시각) · `marketing_agreed` 두 컬럼. 약관 버전은 저장하지 않음 |
| 8 | 폐점 판매자 재입점 | 막음 — `uk_store_user_id` · `uk_store_slug` · `uk_store_store_name` 이 폐점 스토어 행에도 걸려 승인이 실패하므로 신청 단계에서 같은 기준으로 거절. 재입점이 필요해지면 UK 를 바꾸는 V 파일 결정 필요 |
