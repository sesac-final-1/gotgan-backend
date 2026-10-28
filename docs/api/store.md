# store-service API 계약 (설계 — 10/20 · 10/22 · 10/26)

담당 박지연. 입점 신청(`/api/seller/apply/**`) · 판매자 스토어(`/api/seller/store/**` · `/api/seller/dashboard`) · 공개 스토어(`/api/stores/**`) · 다른 서비스(user · admin · order)가 부르는 `/internal/**` 의 **계약**입니다. 엔드포인트마다 PR 하나로 구현하면서 해당 절을 이 문서에 추가합니다. 코드가 정본(springdoc)이고, 이 문서는 호출하는 쪽이 지킬 규칙 설명으로 남깁니다.
근거: 스펙 STR-01~14, storedb V1 · V2, 노션 "10/20 회원 탈퇴 — 판매자는 스토어 폐점 후 탈퇴" 답변 · "10/21 판매자 전환 — 입점 신청 · 승인은 store 쪽 API" 요청 · "10/23 구지훈 - 답변 필요 내용" J-A(3~9절 관리자 내부 계약), 컨벤션 4-7.

## 공통

| 항목 | 값 |
|---|---|
| 주소 | 화면: 게이트웨이 `https://api.gotgan.click` — `/api/stores/**` · `/api/seller/apply/**` · `/api/seller/store/**` · `/api/seller/dashboard`<br>내부: `STORE_SERVICE_URI=http://store-service:8082` (ConfigMap) — `/internal/**` 는 게이트웨이 라우트 없음, 클러스터 내부 전용 |
| 응답 | `ApiResponse<T>` 봉투 (컨벤션 2-4). 오류는 `code` · `message`, `data: null` |
| 금액 | `long`, 원 단위 |
| 시각 | ISO-8601 오프셋 포함 (Asia/Seoul) |
| 인가 | 공개 GET: 로그인 없음 / 입점 신청: BUYER / 판매자: SELLER + `X-Store-Id` 헤더의 스토어만 / 내부: 헤더 없음 |

### 스토어 상태 `status` (10/20 결정 · 휴면 없음 10/23)

| 값 | 뜻 | 거는 쪽 → 푸는 쪽 |
|---|---|---|
| `ACTIVE` | 운영 중 — 승인 직후 상태 | — |
| `SUSPENDED` | 정지 | 관리자 → 관리자 (사유 필수) |
| `CLOSED` | 폐점 — **되돌릴 수 없는 마지막 상태**. 판매자 탈퇴 조건 | 판매자 (폐점 API 는 다음 PR) |

- 폐점은 `deleted_at` 이 아니라 `status` 로 표현합니다. 폐점한 스토어도 주문 · 정산 내역과 내부 API 에서 계속 조회되어야 하기 때문입니다. `deleted_at` 은 컨벤션 3-5 소프트 삭제 자리로만 두고, 채워진 스토어는 모든 조회에서 없는 것으로 봅니다.
- 상태 값은 늘어날 수 있습니다. 호출하는 쪽은 `ACTIVE` 인지 아닌지(또는 필요한 값인지)로 판단하고, 모르는 값이 와도 실패하지 않게 해 주세요.

---

## 1. 스토어 상세 (내부) — `GET /internal/stores/{storeId}` (STR-14 · 회원 탈퇴 · 주문 배송비)

user-service 판매자 탈퇴(`DELETE /api/users/me`)가 스토어 폐점 여부를 확인할 때, admin-service 스토어 관리가 부릅니다. chat-service 도 채팅 상대 표시 이름(`storeName`)과 정지 · 폐점 스토어의 새 스토어 톡 차단(`status` 가 `ACTIVE` 가 아니면 · 404 도 차단, 그 밖의 실패는 막지 않음 — `chat.md` D-1)에 씁니다(1분 캐시). order-service 는 주문 생성 때 스토어별 배송비 계산에 씁니다(아래 배송 정책). DB 조회만 하므로 재시도 가능(GET).

```json
{
  "code": "SUCCESS",
  "message": "스토어를 조회했습니다.",
  "data": {
    "storeId": 1, "userId": 2, "storeName": "곳간상회", "status": "ACTIVE",
    "shippingFee": 3000, "freeShippingThreshold": 30000, "courier": "우체국택배"
  }
}
```

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| 스토어 있음 (상태 무관, `CLOSED` 포함) | 조회 | **200** `{storeId, userId, storeName, status, shippingFee, freeShippingThreshold, courier}` | user: `status == CLOSED` 일 때만 탈퇴 진행. `userId` 가 요청자와 다르면 탈퇴 거부 권장. order: 아래 규칙으로 배송비 계산 |
| 없는 스토어 · 소프트 삭제된 스토어 | — | **404** `STORE_NOT_FOUND` | user: 탈퇴 거부(안전한 쪽). 서킷 실패로 세지 않음 |
| `storeId` 가 숫자가 아님 | — | **400** `INVALID_INPUT` | 호출 코드 버그 |
| store 장애 · 타임아웃 | — | 연결 실패 · 5xx | user: 서킷 `store` 로 집계, 탈퇴 차단 |

- 응답 필드는 **추가만** 합니다(admin 스토어 상세에서 필요한 필드를 붙일 예정). 호출하는 쪽은 모르는 필드를 무시해 주세요.

**배송 정책 (10/28 추가, 배송비 A안)** — store 는 규칙만 알려 주고 계산은 order 가 합니다.

| 필드 | 타입 | 뜻 | null |
|---|---|---|---|
| `shippingFee` | long 원 | 스토어 묶음 기본 배송비 (승인 직후 3,000) | 없음 |
| `freeShippingThreshold` | long 원 | 무료배송 기준 금액 | **무료배송 없음** |
| `courier` | string | 기본 택배사 이름 (예: `CJ대한통운`) | 아직 안 정함 (승인 직후) |

- 배송비 = 한 주문에서 **이 스토어 상품 합계**(가격 × 수량)가 `freeShippingThreshold` 이상이면 0원, 아니면 `shippingFee`. 스토어마다 한 번만 붙습니다(상품 · 수량별 배송비 없음).
- 주문 시점 배송비는 order 가 스냅샷으로 저장합니다 — 판매자가 나중에 바꿔도 기존 주문은 그대로.
- 따로 `/shipping-policy` API 는 두지 않습니다(10/26 결정). 여러 스토어 일괄 조회도 없음 — 주문의 스토어 수만큼 호출.

## 2. 입점 신청 — `POST /api/seller/apply` (STR-01 · 02 · 11)

구매자(BUYER)가 입점 신청 화면(`/seller/apply`, 4단계 폼)을 제출합니다. 신청자는 게이트웨이가 넣는 `X-User-Id` 이고, 승인 때 `StoreApproved.userId` 로 그대로 나갑니다. 서류는 먼저 2-1절 presign 으로 S3 에 올리고 받은 키를 보냅니다.

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
| `intro` | 선택, 5000자 이하. HTML 은 sanitizer 로 정화해 저장(서식 · 문단 · 링크만) |
| `representativeProducts` | **2~10개**. `name`(100자 이하) · `subCategory`(세부 분류 이름, 50자 이하) 필수 · `price` 1 이상. 심사 화면 표시용이라 받은 모양 그대로 JSON 컬럼에 저장 |
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
- 이 API 는 이벤트를 발행하지 않습니다. `StoreApproved` 는 승인 API(5절)에서 발행합니다.

## 2-1. 입점 서류 업로드 주소 발급 — `POST /api/seller/apply/documents/presign` (STR-02)

브라우저가 비공개 seller-docs 버킷에 **직접** 올립니다(서버를 거치지 않음). product 이미지 presign(`product.md` 17절)과 같은 모양에 `docType` 만 더했습니다.

```json
{ "files": [
    { "docType": "BUSINESS_REG",  "contentType": "application/pdf", "size": 482113 },
    { "docType": "PRODUCT_PHOTO", "contentType": "image/jpeg",      "size": 834512 } ] }
```

**200** `data` = 요청과 **같은 순서**의 배열

```json
[ { "key": "applications/4/3f2a0c5e-7d1b-4c1e-9a1f-0b2c3d4e5f60.pdf",
    "uploadUrl": "https://sesac-gojigi-seller-docs-4380.s3.ap-northeast-2.amazonaws.com/applications/4/...",
    "expiresAt": "2026-10-26T15:10:00+09:00" }, ... ]
```

| 경우 | 처리 | 응답 | 화면 권장 처리 |
|---|---|---|---|
| `X-User-Id` 없음 | — | **401** `UNAUTHORIZED` | 로그인으로 |
| `files` 누락 · 항목 null · 모르는 `docType` · `contentType` 빈 값 · `size` 누락 | — | **400** `INVALID_INPUT` | 호출 코드 버그 |
| 개수가 1~13개 밖 | 발급 안 함 | **400** `APPLY_DOCUMENT_INVALID` | 나눠서 다시 요청 |
| 형식 위반 — 서류 3종은 PDF · JPG · PNG, 상품 사진은 JPG · PNG | 발급 안 함(하나만 틀려도 전체) | **400** `APPLY_DOCUMENT_INVALID`, 메시지에 "n번째 파일" | 그 파일만 다시 고르기 |
| 크기 0 이하 · 10MB 초과 | 발급 안 함 | **400** `APPLY_DOCUMENT_INVALID`, 메시지에 "n번째 파일" | 〃 |
| 자격 증명을 얻지 못함 | — | **502** `EXTERNAL_API_ERROR` | 잠시 후 다시 |
| 정상 | 키 `applications/{X-User-Id}/{UUID}.{pdf\|jpg\|png}`(확장자는 파일 이름이 아니라 `contentType` 으로), presigned PUT 10분 | **200** | 아래 업로드 순서 |

**업로드 순서**: ① 이 API → ② 파일마다 `uploadUrl` 로 `PUT` — `Content-Type` 은 요청의 `contentType` 과 같게, 본문은 발급 때 말한 크기의 파일 그대로 → ③ 2절 `documents[]` 에 `{docType, key, originalName}` 으로 제출.

- **`Content-Type` · `Content-Length` 가 서명에 들어갑니다.** 발급 때와 다른 형식 · 크기로 PUT 하면 S3 가 **403**(`SignatureDoesNotMatch`) — 10MB 넘는 파일은 버킷에 올라가지 않습니다. 브라우저 `fetch(url, {method: 'PUT', body: file, headers: {'Content-Type': file.type}})` 는 `Content-Length` 를 자동으로 붙이므로 따로 할 일은 없습니다. 만료(10분) 뒤 PUT 도 403 → 다시 발급.
- **서류 구성(3종 각 1 + 사진 3~10)은 여기서 보지 않고 2절 제출 때 검사합니다.** 폼 단계마다 나눠 불러도 됩니다. 이미 스토어가 있는지 · 진행 중 신청이 있는지도 2절에서 확인합니다.
- 2절은 S3 `HeadObject` 로 형식 · 크기를 **다시** 확인합니다(같은 기준). 이 API 는 1차 관문입니다.
- 서명은 `Content-Type` 만 보고 내용은 보지 않으므로, 올라온 파일은 Lambda `upload-guard`(10/27, `serverless/upload-guard`)가 앞부분 바이트로 실제 PDF · JPG · PNG 인지 확인해 아니면 **지웁니다**(알림 메일). 지워진 키로 2절을 제출하면 S3 에 없는 파일로 400 `APPLY_DOCUMENT_INVALID`.
- 서명은 store 안에서 계산하므로 S3 를 호출하지 않습니다. 버킷 CORS(PUT · HEAD, vercel · `localhost:5173`) · store IRSA `s3:PutObject` 는 설정돼 있습니다.
- 올리고 신청하지 않은 파일은 버킷에 남습니다(정리 규칙 없음 — 학습 프로젝트라 보류).

---

## 관리자 내부 API 공통 (3~9절, 호출자 admin-service)

admin-service 가 `/api/admin/applications/**` · `/api/admin/stores/**` 를 받아 그대로 부르는 API 입니다. 관리자 인가는 게이트웨이 · admin 이 끝냈다고 보고 store 는 다시 확인하지 않습니다.

| 항목 | 규칙 |
|---|---|
| 목록 | **페이징 없이 전체 배열** — 컨벤션 4-7(관리자 입점 심사 · 스토어는 전체 1회 로드 + 화면에서 검색 · 정렬 · 페이징). `status` 는 선택 필터, 생략하면 전체 |
| 처리자 | 상태를 바꾼 쪽은 `actor` = `APPLICANT`(신청자) · `ADMIN` · `SYSTEM`(7일 자동 반려) / 스토어는 `SELLER` · `ADMIN`. **관리자 개인은 저장하지 않음**(storedb V1) — 화면의 "곳간 운영팀 · 정다은" 같은 이름은 내려 주지 않습니다 |
| 사유 | 반려 · 보완요청 · 정지 · 해제는 사유 필수, 255자 이하. 비거나 길면 400 `INVALID_INPUT` |
| 이벤트 | 상태 변경과 같은 트랜잭션에서 아웃박스(storedb `outbox_event`)에 남기고 커밋 직후 발행. 발행이 실패해도 API 응답은 그대로이고 발행기가 30초마다 다시 보냄 — **최소 1회 전달**(같은 `eventId` 봉투가 두 번 올 수 있음, 구독자 `processed_event` 로 거름) |
| 구현 시기 | 구현됨: 3 · 4 · 5 · 6절 / 4주차: 7 · 8 · 9절 · 1절 확장(배송 필드 3개는 구현됨). 그 전까지 admin 은 이 문서 기준으로 개발 |

### 입점 신청 상태 `status`

| 값 | 뜻 | 바뀌는 곳 |
|---|---|---|
| `PENDING` | 심사대기 | 신청(2절) · 재제출(4주차) |
| `SUPPLEMENT` | 보완요청 — 신청자 재제출 대기 | 보완요청(7절) |
| `APPROVED` | 승인 — 스토어 생성됨 | 승인(5절) |
| `REJECTED` | 반려 | 반려(6절) · 7일 미보완 자동 반려(4주차) |

## 3. 입점 신청 목록 (내부) — `GET /internal/applications?status=` (STR-03)

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| `status` 없음 · 위 4개 값 중 하나 | 조회, 제출 최신순 | **200** 배열 (없으면 `[]`) | 화면 탭 · 건수는 전체를 받아 클라이언트에서 계산 |
| `status` 가 모르는 값 | — | **400** `INVALID_INPUT` | 호출 코드 버그 |

```json
[
  {
    "applicationId": 27,
    "applicationNo": "AP-2610-027",
    "storeName": "곳간상회",
    "ownerName": "홍길동",
    "bizType": "INDIVIDUAL",
    "categoryId": 1,
    "expectedMonthlySales": "FROM_5M_TO_20M",
    "status": "PENDING",
    "submittedAt": "2026-10-22T14:05:11+09:00",
    "statusChangedAt": "2026-10-22T14:05:11+09:00"
  }
]
```

- 카테고리 이름은 store 가 갖고 있지 않습니다 — admin 이 product 카테고리 목록으로 `categoryId` 를 이름으로 바꿔 주세요.
- 접수번호 `applicationNo` 는 2절과 같은 계산값입니다.

## 4. 입점 신청 상세 (내부) — `GET /internal/applications/{applicationId}` (STR-03 · 10)

신청서 전체 + 서류 열람 주소 + 상태 이력 + 같은 사업자번호의 이전 신청 · 스토어(중복 입점 확인 근거)를 돌려줍니다.

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| 신청 있음 (상태 무관) | 조회 + 서류마다 presigned GET 발급 | **200** 아래 응답 | 서류 링크는 10분 안에 열기, 만료되면 상세를 다시 조회 |
| 없는 신청 | — | **404** `APPLY_NOT_FOUND` | 목록으로 |
| `applicationId` 가 숫자가 아님 | — | **400** `INVALID_INPUT` | 호출 코드 버그 |

```json
{
  "applicationId": 27,
  "applicationNo": "AP-2610-027",
  "status": "SUPPLEMENT",
  "statusReason": "통신판매업 신고증 사본이 흐려 확인이 어렵습니다",
  "submittedAt": "2026-10-22T14:05:11+09:00",
  "statusChangedAt": "2026-10-23T10:00:00+09:00",
  "storeId": null,

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
  "representativeProducts": [{ "name": "리넨 셔츠", "subCategory": "셔츠", "price": 39000 }],
  "expectedMonthlySales": "FROM_5M_TO_20M",
  "salesChannel": "https://instagram.com/gotgan.store",
  "settlementAccount": { "bank": "국민", "number": "000000-00-000000", "holder": "홍길동" },
  "marketingAgreed": false,
  "termsAgreedAt": "2026-10-22T14:05:11+09:00",

  "documents": [
    {
      "documentId": 101,
      "docType": "ECOMMERCE_REG",
      "originalName": "통신판매업신고증.pdf",
      "contentType": "application/pdf",
      "fileSize": 482113,
      "current": true,
      "uploadedAt": "2026-10-22T14:05:11+09:00",
      "url": "https://sesac-gojigi-seller-docs-4380.s3.ap-northeast-2.amazonaws.com/applications/4/...?X-Amz-...",
      "urlExpiresAt": "2026-10-26T15:10:00+09:00"
    }
  ],
  "history": [
    { "fromStatus": null, "toStatus": "PENDING", "actor": "APPLICANT", "reason": null, "changedAt": "2026-10-22T14:05:11+09:00" },
    { "fromStatus": "PENDING", "toStatus": "SUPPLEMENT", "actor": "ADMIN", "reason": "통신판매업 신고증 사본이 흐려 확인이 어렵습니다", "changedAt": "2026-10-23T10:00:00+09:00" }
  ],
  "sameBizRegNo": {
    "applications": [{ "applicationId": 9, "applicationNo": "AP-2609-009", "storeName": "곳간상점", "status": "REJECTED", "submittedAt": "2026-09-14T11:00:00+09:00" }],
    "stores": []
  }
}
```

| 필드 | 설명 |
|---|---|
| `storeId` | 승인된 신청이면 만들어진 스토어 ID, 아니면 `null` |
| `documents` | 지금 서류(`current: true`)와 재제출로 바뀐 이전 서류(`false`)를 모두 담음 — 이전 제출본과 비교용(STR-09). 종류별 · 올린 순서 |
| `documents[].url` | seller-docs 버킷 presigned GET, **10분** 만료. 서명은 store 안에서 계산하므로 S3 장애여도 상세는 200 (링크만 열리지 않음) |
| `history` | 오래된 순. 신청자 재제출(`SUPPLEMENT → PENDING`, `APPLICANT`)과 자동 반려(`SYSTEM`)도 여기에 |
| `sameBizRegNo` | 같은 사업자등록번호의 **이 신청을 뺀** 다른 신청 · 스토어(폐점 포함, 소프트 삭제 제외). `stores[]` 는 `{storeId, storeName, status}` |
| 체크리스트 | **서버는 받지도 검사하지도 않음**(STR-10 "체크 결과는 저장하지 않음") — 화면이 네 항목을 모두 체크해야 승인 버튼을 켬. 중복 입점 판단 근거가 `sameBizRegNo` |

## 5. 입점 승인 (내부) — `PATCH /internal/applications/{applicationId}/approve` (STR-04)

본문 없음. 신청서에서 스토어(`ACTIVE`)를 만들고 커밋 뒤 `StoreApproved` 를 발행합니다.

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| `PENDING` · `SUPPLEMENT` | 스토어 생성(스토어명 · 주소 · 소개 복사, `cs_phone` = 담당자 휴대폰) + 스토어 이력(`NULL → ACTIVE`, `ADMIN`) + 신청 `APPROVED` + 신청 이력을 한 트랜잭션으로 | **200** `{applicationId, status: "APPROVED", storeId, decidedAt}` | 상세 다시 조회 |
| 이미 `APPROVED` · `REJECTED` | — | **409** `APPLY_INVALID_STATUS` | 상세 다시 조회 (다른 관리자가 먼저 처리) |
| 신청자에게 이미 스토어가 있음 (폐점 포함) | — | **409** `APPLY_STORE_ALREADY_EXISTS` | 반려 |
| 스토어 주소 · 스토어명이 그사이 다른 스토어와 겹침 | — | **409** `APPLY_SLUG_DUPLICATED` · `APPLY_STORE_NAME_DUPLICATED` | 보완요청으로 변경 안내 (STR-04) |
| 승인 · 재제출 · 자동 반려가 동시에 저장됨 | 한쪽만 반영(`@Version`) | **409** `APPLY_INVALID_STATUS` | 상세 다시 조회 |
| 없는 신청 | — | **404** `APPLY_NOT_FOUND` | 목록으로 |

**발행 이벤트 `StoreApproved`** (config `docs/events.md`, 구독 user · settlement · 알림 Lambda · admin 알림 센터)

```json
{ "storeId": 3, "userId": 4, "storeName": "곳간상회", "sellerEmail": "seller@example.com",
  "settlementAccount": { "bank": "국민", "number": "000000-00-000000", "holder": "홍길동" } }
```

- `userId` = 신청 때의 `X-User-Id`, `sellerEmail` = 신청서 `managerEmail`.
- `eventId` 는 신청 ID 로 정해지는 값(`StoreApproved:{applicationId}` 의 이름 기반 UUID)이라 **다시 보내도 같은 `eventId`** — 구독자 `processed_event` 가 중복을 걸러 줍니다(노션 10/23 user 요청). `occurredAt` = 응답의 `decidedAt` 이라 다시 보내도 같습니다(admin 알림함 키).
- 승인과 같은 트랜잭션에서 아웃박스에 남겨 커밋 직후 보냅니다. SNS 가 실패하거나 1초 넘게 걸리면 응답은 그대로 200 이고 발행기가 30초마다 다시 보냅니다(10회 실패 시 store error 로그). 승인 응답이 SNS 때문에 admin read-timeout(3초)을 넘지 않습니다.
- 판매자 권한은 user 가 이 이벤트를 받아 Keycloak 에 `store_id` · `SELLER` 를 넣은 뒤 **다음 토큰부터** 반영됩니다. 승인 응답 시점에는 아직 판매자센터에 못 들어갈 수 있습니다.

## 6. 입점 반려 (내부) — `PATCH /internal/applications/{applicationId}/reject` (STR-05)

요청 `{ "reason": "사업자등록증 정보가 신청서와 다릅니다" }` (필수, 255자 이하).

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| `PENDING` · `SUPPLEMENT` | 신청 `REJECTED` · `statusReason` = 사유 + 이력. 30일 재신청 제한은 이 시각부터 | **200** `{applicationId, status: "REJECTED", decidedAt}` | 상세 다시 조회 |
| 사유 없음 · 255자 초과 | — | **400** `INVALID_INPUT` | 사유 입력 안내 |
| 이미 `APPROVED` · `REJECTED` · 동시 저장 | — | **409** `APPLY_INVALID_STATUS` | 상세 다시 조회 |
| 없는 신청 | — | **404** `APPLY_NOT_FOUND` | 목록으로 |

**발행 이벤트 `StoreApplicationRejected`** (구독 알림 Lambda — 반려 메일 · admin 알림 센터 — 화면 알림)
`{applicationId, userId, storeName, sellerEmail, reason, decidedAt}` — 이름은 `serverless/order-notification` 이 읽는 그대로.

## 7. 입점 보완요청 (내부) — `PATCH /internal/applications/{applicationId}/supplement` (STR-09)

```json
{ "message": "사본이 흐려 확인이 어렵습니다", "requestedDocs": ["ECOMMERCE_REG"] }
```

| 필드 | 규칙 |
|---|---|
| `message` | 필수, 255자 이하. `statusReason` · 이력 사유로 저장 |
| `requestedDocs` | 다시 낼 서류 종류 `BUSINESS_REG` · `ECOMMERCE_REG` · `BANK_COPY` · `PRODUCT_PHOTO`, 0~4개 · 중복 금지(서류가 아닌 내용 보완이면 `[]`) |
| 기한 | 받지 않음 — 서버가 **보완요청 시각 + 7일**로 계산(`dueAt`). 7일 자동 반려 스케줄러와 같은 기준 |

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| `PENDING` | 신청 `SUPPLEMENT` + 이력 | **200** `{applicationId, status: "SUPPLEMENT", dueAt}` | 상세 다시 조회 |
| 이미 `SUPPLEMENT` · `APPROVED` · `REJECTED` · 동시 저장 | — | **409** `APPLY_INVALID_STATUS` | 상세 다시 조회 (보완 중이면 재제출을 기다림) |
| 형식 오류 (위 규칙) | — | **400** `INVALID_INPUT` | 입력 안내 |
| 없는 신청 | — | **404** `APPLY_NOT_FOUND` | 목록으로 |

**발행 이벤트 `StoreApplicationSupplementRequested`** (구독 알림 Lambda — 보완요청 메일 · admin 알림 센터 — 화면 알림)
`{applicationId, userId, storeName, sellerEmail, requestedDocs[], message, dueAt}`. 메일에 그대로 찍히므로 `requestedDocs` 는 **한글 이름**(`사업자등록증` · `통신판매업 신고증` · `통장 사본` · `상품 사진`)으로 바꿔 보냅니다. 요청 본문의 enum 과 다릅니다.

## 8. 스토어 목록 (내부) — `GET /internal/stores?status=` (STR-14)

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| `status` 없음 · `ACTIVE` · `SUSPENDED` · `CLOSED` | 소프트 삭제 제외, 개설 최신순 | **200** 배열 (없으면 `[]`) | 검색 · 정렬 · 페이징은 화면에서 |
| `status` 가 모르는 값 | — | **400** `INVALID_INPUT` | 호출 코드 버그 |

```json
[
  { "storeId": 1, "storeName": "곳간상회", "slug": "gotgan-store", "userId": 2, "ownerName": "홍길동",
    "bizRegNo": "000-00-00001", "grade": "SPROUT", "status": "ACTIVE", "statusReason": null,
    "createdAt": "2026-10-15T09:00:00+09:00" }
]
```

- 대표자 · 사업자번호는 원래 신청서(`store.store_application_id`)에서 가져옵니다.
- 스토어 상세(관리자 상세 화면)는 1절을 씁니다 — 4주차에 1절 응답에 관리자용 필드와 상태 이력을 **추가**합니다(아래 "1절 확장").

**1절 확장 (4주차)** — 지금 4개 필드 뒤에 붙습니다. user · order 는 기존 필드만 읽으면 됩니다.
`slug` · `ownerName` · `companyName` · `bizRegNo` · `csPhone` · `grade` · `shippingFee` · `freeShippingThreshold` · `courier` · `dispatchNote` · `statusReason` · `applicationId` · `createdAt` · `statusHistory[{fromStatus, toStatus, actor, reason, changedAt}]`(오래된 순)

## 9. 스토어 정지 · 해제 (내부) — `PATCH /internal/stores/{storeId}/status` (STR-13)

요청 `{ "status": "SUSPENDED", "reason": "허위 광고 신고 3건 확인" }` — `status` 는 `SUSPENDED`(정지) · `ACTIVE`(해제)만, `reason` 필수 255자 이하.

| 경우 | 처리 | 응답 | 호출자 권장 처리 |
|---|---|---|---|
| `ACTIVE → SUSPENDED` · `SUSPENDED → ACTIVE` | 상태 · `statusReason` 변경 + 스토어 이력(`ADMIN`) | **200** 1절 응답 | 상세 갱신 |
| 이미 그 상태 | 바꾸지 않음 · 이벤트 없음 | **200** 1절 응답 (멱등 — 두 번 눌러도 안전) | 그대로 |
| 폐점(`CLOSED`) 스토어 | — | **409** `STORE_INVALID_STATUS_TRANSITION` | "폐점한 스토어는 바꿀 수 없음" 안내 |
| `status` 가 `CLOSED` · 그 밖의 값, 사유 없음 · 255자 초과 | — | **400** `INVALID_INPUT` | 폐점은 판매자만(`PATCH /api/seller/store/close`, 다음 PR) |
| 없는 스토어 · 소프트 삭제 | — | **404** `STORE_NOT_FOUND` | 목록으로 |

**발행 이벤트 `StoreStatusChanged`** `{storeId, status}` (구독 product — 상품 노출 숨김 · settlement — 지급 보류). 구독자는 `occurredAt` 으로 옛 이벤트를 거릅니다(events.md A안). 같은 이벤트를 폐점 API 도 `CLOSED` 로 발행합니다.

## 10. 오류 코드 (`docs/api/error-codes.md`)

도메인 코드는 store-service 안 도메인별 enum(`{domain}/exception/{Domain}ErrorCode`), 공통 코드는 `libs/common-web` 의 `CommonErrorCode` 입니다.

| 코드 | HTTP | enum | 언제 |
|---|---|---|---|
| `STORE_NOT_FOUND` | 404 | `StoreErrorCode` | 없거나 소프트 삭제된 스토어 (1 · 9절) |
| `STORE_INVALID_STATUS_TRANSITION` | 409 | `StoreErrorCode` | 폐점 스토어 정지 · 해제 (9절) |
| `APPLY_NOT_FOUND` | 404 | `ApplyErrorCode` | 없는 입점 신청 (4~7절) |
| `APPLY_INVALID_STATUS` | 409 | `ApplyErrorCode` | 승인 · 반려 · 보완요청을 할 수 없는 신청 상태, 동시 저장에서 진 쪽 (5~7절) |
| `APPLY_STORE_ALREADY_EXISTS` | 409 | `ApplyErrorCode` | 신청자에게 이미 스토어가 있음 — 폐점 포함 (2 · 5절) |
| `APPLY_IN_PROGRESS` | 409 | `ApplyErrorCode` | 심사대기 · 보완요청 중인 신청이 있음 (2절) |
| `APPLY_REAPPLY_RESTRICTED` | 409 | `ApplyErrorCode` | 반려 후 30일 안에 다시 신청 (2절, STR-11) |
| `APPLY_SLUG_DUPLICATED` | 409 | `ApplyErrorCode` | 스토어 주소가 스토어 · 진행 중 신청과 겹침 (2절), 승인 때 다른 스토어와 겹침 (5절) |
| `APPLY_STORE_NAME_DUPLICATED` | 409 | `ApplyErrorCode` | 스토어명이 스토어 · 진행 중 신청과 겹침 (2절), 승인 때 다른 스토어와 겹침 (5절) |
| `APPLY_DOCUMENT_INVALID` | 400 | `ApplyErrorCode` | 서류 종류 · 개수 · 형식 · 크기 · 키 위반, S3 에 없는 파일 (2절), 업로드 주소 발급의 개수 · 형식 · 크기 위반 (2-1절) |
| `INVALID_INPUT` (공통) | 400 | `CommonErrorCode` | 경로 · 요청 형식 오류, 사유 누락 · 255자 초과 (6 · 7 · 9절) |
| `UNAUTHORIZED` (공통) | 401 | `CommonErrorCode` | 사용자 헤더 없음 (2 · 2-1절) |
| `EXTERNAL_API_ERROR` (공통) | 502 | `CommonErrorCode` | S3 확인 실패 (2절), 업로드 주소 발급 실패 — 자격 증명 (2-1절) |

호출하는 서비스는 404 · 400 을 자기 도메인 코드로 바꾸거나 그대로 전달하고, **서킷 실패로 세지 않습니다**(`ignore-exceptions` = `BusinessException`).

## 11. 호출 쪽 준비 사항

**user (이경석)**
- `MockStoreClient` 를 `@HttpExchange` 클라이언트(`GET /internal/stores/{storeId}`)로 교체. 서킷 브레이커 인스턴스 이름은 **`store`**, 재시도 가능(GET).
- user-service `StoreStatus` enum 값(ACTIVE · SUSPENDED · CLOSED)은 이 문서와 같습니다. 바꿀 것 없음.
- 지금은 폐점 API 가 없어 `CLOSED` 스토어가 생기지 않습니다 → 판매자 탈퇴는 폐점 API PR 전까지 계속 409 `USER_SELLER_STORE_NOT_CLOSED`.

**admin (구지훈)**: 3~9절을 기준으로 먼저 개발해 주세요. 서킷 브레이커 이름은 **`store`**, 재시도는 GET(3 · 4 · 8 · 1절)만.
- 목록(3 · 8절)은 전체 배열이라 `PageResponse` 로 감싸지 않습니다. 스펙 STR-03 의 "서버 페이징" 문구는 컨벤션 4-7 과 어긋나 이 문서를 따릅니다.
- 신청 전체의 처리 이력을 한 화면에 모으는 API 는 만들지 않습니다 — 신청별 이력은 4절 `history`. 화면에 꼭 필요하면 요청해 주세요.
- 처리자 이름이 없습니다(`actor` 만) — 목업의 관리자 이름 표시는 "관리자"처럼 바꿔 주세요.
- 업종별 추가 서류가 없어져(10/22) 체크리스트의 인증서 항목 · 보완요청 예시 "KC 인증서 제출" · 카테고리별 추가 서류 표시는 빼 주세요. 보완요청 서류 선택지는 7절 4종입니다.
- 목록의 카테고리 이름 · 관리자 대시보드 "심사 대기 · 보완 중" 건수는 3절 배열로 계산해 주세요(따로 통계 API 없음).

**user (이경석)**: 승인(5절)의 `StoreApproved` 는 다시 보내도 `eventId` 가 같습니다. 승인 PR 이 머지되면 노션으로 알리니 테스트 메시지(user 6 · store 3)를 실제 모양에 맞춰 주세요.

**settlement · product**: 정지 · 해제(9절)부터 `StoreStatusChanged` 가 실제로 발행됩니다(4주차).

**user (이경석)**: `StoreApproved` 는 승인 API PR 부터 발행됩니다(2절은 발행 없음). 신청 단계에서 기존 스토어 회원을 409 `APPLY_STORE_ALREADY_EXISTS` 로 막습니다(요청 4번).

**frontend (박지연 — `features/store`)**: 서류는 2-1절 발급 → S3 PUT → 받은 `key` 를 2절 `documents[].key` 로. 4단계 폼 값을 2절 요청 모양으로 매핑. "모두 동의"는 필수 3개만 켜고 마케팅은 따로 체크 · 카테고리별 추가 서류 안내 제거 · 접수 완료 화면의 접수번호는 응답 `applicationNo`.

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

## 10/26 설계 정리 (입점 서류 업로드 주소)

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 경로 | `/api/seller/apply/documents/presign` — 2절과 같은 줄기. 게이트웨이 `/api/seller/apply/**` = BUYER 규칙(뼈대부터 있음)이라 수정 없음. 시트의 `/api/store-applications/...` 는 쓰지 않음 |
| 2 | 모양 | product 17절처럼 여러 개를 한 번에(`files[]` → 같은 순서 배열). 서류마다 허용 형식이 달라 `docType` 추가. 원본 파일명은 받지 않음(2절 `originalName`) |
| 3 | 만료 | 10분 — product presigned PUT · 4절 presigned GET 과 같게 |
| 4 | 크기 서명 | `Content-Length` 를 서명에 포함 → 큰 파일은 업로드 단계에서 403. product 이미지 presign(4주차)도 같은 방식으로 맞출 예정 |
| 5 | 검사 범위 | 개수 1~13 · 형식 · 크기만. 서류 구성 · 기존 스토어 · 진행 중 신청은 2절에서 — 발급은 가볍게 |

## 10/26 설계 정리 (관리자 내부 API)

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 목록 페이징 | 없음 — 전체 배열 + `status` 선택 필터 (컨벤션 4-7, 지훈 님 관리자 화면이 `useClientTable` 로 전체를 받아 탭 건수까지 계산) |
| 2 | 전체 처리 이력 API | 만들지 않음 — 관리자 화면 전용이라 필요하면 admin 쪽 요청으로. 신청별 이력은 상세에 포함 |
| 3 | 서류 열람 | 상세 조회 때 presigned GET 10분. 이전 제출본(`is_current = false`)도 비교용으로 포함 |
| 4 | 체크리스트 | 서버는 받지도 검사하지도 않음(STR-10). 중복 입점 근거로 같은 사업자번호의 다른 신청 · 스토어를 상세에 포함(`idx_store_application_biz_reg_no`) |
| 5 | 보완요청 본문 | `{message, requestedDocs[]}`, 기한은 서버가 +7일. 이벤트의 `requestedDocs` 는 메일용 한글 이름. **`requestedDocs` 는 DB 에 저장하지 않음** — 재제출 화면(4주차)에서 필요해지면 그때 V 파일로 컬럼 추가 |
| 6 | 승인 이벤트 재발행 | `eventId` = `StoreApproved:{applicationId}` 이름 기반 UUID — 신청 하나에 항상 같은 값. 10/28 부터 아웃박스로 발행(아래 10/28 설계 정리) |
| 7 | 정지 · 해제 | `SUSPENDED ↔ ACTIVE` 만. 같은 상태면 200 멱등(이벤트 없음 — product 카테고리 `PATCH` 와 같은 방식), `CLOSED` 는 409, 요청에 `CLOSED` 는 400(폐점은 판매자 API) |
| 8 | 관리자 스토어 상세 | 새 경로 없이 1절 응답에 필드 추가(필드는 추가만 규칙). 상태 이력 조회가 하나 늘지만 user · order 호출 빈도에서 무시할 수준 |
| 9 | 처리자 | `actor` 종류만, 관리자 ID · 이름 없음(storedb V1 그대로) |

## 10/28 설계 정리 (이벤트 발행)

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 발행 방식 | 아웃박스(storedb V4 `outbox_event`, backend #165) — order · settlement 와 같은 방식. 커밋 직후 1회 + 발행기(30초, ShedLock `store-event-outbox`)가 실패 · 남은 행을 다시. 커밋 직후 발행만 하면 SNS 장애 때 `StoreApproved` 가 사라져 판매자 권한이 안 붙음 |
| 2 | SNS 타임아웃 | 호출 1초 · 시도 500ms — admin read-timeout 3초 안에 응답(타임아웃 예산). 늦으면 발행기에 맡김 |
| 3 | `eventId` · `occurredAt` | 부르는 쪽이 정해 아웃박스 행에 저장 — 승인은 신청 ID 이름 기반 UUID · 승인 시각. 다시 보내도 같은 봉투 |
| 4 | 실패 로그 | 9회까지 warn, 10회째 error + 스택 한 번, 그 뒤 한 줄 warn(컨벤션 2-8 반복 실패는 첫 회만 스택) |
