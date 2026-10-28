# review-service API 계약 (설계 — 10/28)

담당 이경석. 리뷰(`/api/reviews/**` · `/api/seller/reviews/**`) · 상품문의(`/api/inquiries/**` · `/api/seller/inquiries/**`) · admin 이 부르는 `/internal/reviews/**` 의 **계약**입니다. 엔드포인트마다 PR 하나로 구현하면서 해당 절을 추가합니다. 코드가 정본(springdoc)이고, 이 문서는 호출하는 쪽이 지킬 규칙 설명으로 남깁니다.
근거: 스펙 REV-01~09 · REV-12, reviewdb V2, 노션 확인 요청 「신고 · 리뷰 블라인드 (B안)」 10/26, 프론트 `features/review`.

## 공통

| 항목 | 값 |
|---|---|
| 주소 | 화면: 게이트웨이 `https://api.gotgan.click` — `/api/reviews/**` · `/api/inquiries/**`(GET 공개, 그 외 로그인) · `/api/seller/reviews/**` · `/api/seller/inquiries/**`(SELLER)<br>내부: `REVIEW_SERVICE_URI=http://review-service:8087` — `/internal/**` 는 게이트웨이 라우트 없음 |
| 응답 | `ApiResponse<T>` 봉투 (컨벤션 2-4). 오류는 `code` · `message`, `data: null` |
| 시각 | ISO-8601 오프셋 포함 (Asia/Seoul) |
| 작성 자격 | `OrderItemConfirmed` 를 받아 저장한 `review_eligibility` 만 본다 — order 를 호출하지 않는다 (REV-01) |
| 상품 표시값 | 상품명 · 옵션명 · 대표 사진은 구매확정 시점 스냅샷(자격에 저장) — review 는 product 를 호출하지 않는다. `OrderItemConfirmed` 에 `optionName` · `thumbnailUrl` · `buyerDisplayName` 추가 필요(order) |
| 본문 | 일반 텍스트. 저장 전 OWASP sanitizer 로 태그를 모두 지운다(엔티티로 쓴 태그 포함). 화면은 React 기본 이스케이프로 출력 |

---

## 1. 리뷰 작성 — `POST /api/reviews` (REV-02)

마이페이지 "작성 가능한 리뷰"(`MyReviewModal`)에서 등록합니다. 작성자는 게이트웨이가 넣는 `X-User-Id` 입니다. 사진은 아직 받지 않습니다 — 업로드 주소 발급(REV-03)과 함께 `imageKeys`(최대 3장)를 추가할 예정입니다.

```json
{ "orderItemId": 11, "rating": 5, "title": "핏이 진짜 예뻐요",
  "content": "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.", "fit": "TRUE_TO_SIZE" }
```

| 필드 | 규칙 |
|---|---|
| `orderItemId` | 필수 — 구매확정한 내 상품주문 |
| `rating` | 필수, 1~5 |
| `title` | 선택, 100자 이하 (화면에 입력칸은 아직 없음) |
| `content` | 필수, 20~2,000자 |
| `fit` | 선택 — `SMALL`(작아요) · `TRUE_TO_SIZE`(정사이즈) · `LARGE`(커요). 패션 · 신발가방만 보내고 그 외 생략 |

응답 **201** `{ "reviewId": 31 }`

| 경우 (검사 순서) | 응답 |
|---|---|
| 형식 위반 · 태그를 지우니 본문이 빔 | 400 `INVALID_INPUT` |
| `X-User-Id` 없음 | 401 `UNAUTHORIZED` |
| 작성 자격 없음 · 다른 회원의 상품주문 (구분하지 않음) | 403 `REVIEW_NOT_ELIGIBLE` |
| 이미 리뷰를 쓴 상품주문 — **삭제한 리뷰 포함**, 동시 제출도 1건만 | 409 `REVIEW_ALREADY_WRITTEN` |
| 구매확정 후 30일이 지남 (`expires_at` 시각까지 작성 가능) | 409 `REVIEW_WRITE_EXPIRED` |

- 작성자 표시 이름(`writerDisplayName`)은 자격의 `buyer_display_name`(닉네임, 없으면 마스킹 실명)을 복사합니다. 이 값은 `OrderItemConfirmed.buyerDisplayName` 으로 받을 예정이라 order payload 추가가 필요합니다.

## 2. 내 리뷰 수정 — `PUT /api/reviews/{reviewId}` (REV-05)

마이페이지 "내가 쓴 리뷰"의 수정 버튼(`MyReviews`)에서 씁니다. 작성 모달을 채운 상태로 다시 열어 같은 값을 보내는 형태라 **통째로 바꿉니다** — 한줄 요약 · 사이즈 체감을 비워 보내면 지워집니다.

```json
{ "rating": 4, "title": "길이가 조금 길어요",
  "content": "키 160인데 기장이 살짝 길어서 수선했어요. 그 외에는 만족합니다.", "fit": "LARGE" }
```

- 필드 규칙은 1절 작성과 같습니다(`orderItemId` 만 없음).
- **기한 없음** — 작성 기한(30일)이 지나도 수정할 수 있습니다.
- 판매자 답글은 그대로 남습니다.

응답 **200** `{ "reviewId": 31, "rating": 4, "title": "…", "content": "…(태그를 지운 값)", "fit": "LARGE" }`

| 경우 | 응답 |
|---|---|
| 형식 위반 · 태그를 지우니 본문이 빔 | 400 `INVALID_INPUT` |
| `X-User-Id` 없음 | 401 `UNAUTHORIZED` |
| 없는 · 삭제된 · 다른 회원의 리뷰 (구분하지 않음) | 404 `REVIEW_NOT_FOUND` |
| 관리자가 블라인드한 리뷰 — 사유가 된 내용을 고쳐 노출을 되살리지 못하게, 해제는 관리자만 | 409 `REVIEW_BLINDED` |

## 3. 내 리뷰 삭제 — `DELETE /api/reviews/{reviewId}` (REV-05)

마이페이지 "내가 쓴 리뷰"의 삭제 버튼(`MyReviews`)에서 씁니다. **소프트 삭제**(`deleted_at`)라 상품 리뷰 목록 · 평균 별점 · 리뷰 수 · admin 내부 조회(404)에서 빠집니다.

- 블라인드된 리뷰도 지울 수 있습니다.
- 상품주문당 1개라 **지운 뒤 같은 상품주문에 다시 쓸 수 없습니다** (작성 409 `REVIEW_ALREADY_WRITTEN`). 화면 확인 문구에 안내해 주세요.

응답 **200** `{ "code": "SUCCESS", "message": "리뷰를 삭제했습니다.", "data": null }`

| 경우 | 응답 |
|---|---|
| `X-User-Id` 없음 | 401 `UNAUTHORIZED` |
| 없는 · 이미 삭제된 · 다른 회원의 리뷰 (구분하지 않음) | 404 `REVIEW_NOT_FOUND` |

## 4. 내가 쓴 리뷰 — `GET /api/reviews/me?page=0&size=10` (REV-05)

마이페이지 리뷰 관리(`/mypage/reviews`) "내가 쓴 리뷰" 탭(`MyReviews`). 게이트웨이는 `/api/reviews/**` GET 을 공개로 통과시키므로 로그인 확인은 review 가 합니다(`X-User-Id` 없으면 401).

- 삭제한 리뷰 제외, **최신 작성순**. 블라인드된 리뷰도 `blinded: true` 로 보입니다(화면: "관리자에 의해 블라인드 처리됨", 수정 버튼 숨김).
- `page` 0부터, `size` 1~50(기본 10). 탭 숫자는 `totalElements`.

```json
{ "content": [ { "reviewId": 31, "orderItemId": 11, "productId": 101, "productName": "린넨 오버핏 셔츠",
    "optionName": "오트밀 / M", "thumbnailUrl": "https://…/products/1/a1b2.jpg", "rating": 5, "title": null,
    "content": "…", "fit": "TRUE_TO_SIZE", "blinded": false, "replyContent": "소중한 후기 감사합니다.",
    "repliedAt": "2026-10-21T09:30:00+09:00", "createdAt": "2026-10-20T14:00:00+09:00" } ],
  "page": 0, "size": 10, "totalElements": 1, "totalPages": 1, "hasNext": false }
```

| 경우 | 응답 |
|---|---|
| `page` < 0 · `size` 1~50 밖 · 숫자 아님 | 400 `INVALID_INPUT` |
| `X-User-Id` 없음 | 401 `UNAUTHORIZED` |

## 5. 작성 가능한 리뷰 — `GET /api/reviews/me/writable` (REV-05)

"작성 가능한 리뷰" 탭과 작성 모달 상단. 구매확정 후 30일 안이고 아직 리뷰가 없는 내 상품주문 — **삭제한 리뷰가 있는 상품주문은 빠집니다**(다시 쓸 수 없음). **기한 임박순**, 기한 30일이라 페이징 없음.

```json
[ { "orderItemId": 11, "productId": 101, "productName": "린넨 오버핏 셔츠", "optionName": "오트밀 / M",
    "thumbnailUrl": "https://…/products/1/a1b2.jpg",
    "confirmedAt": "2026-10-20T14:00:00+09:00", "expiresAt": "2026-11-19T14:00:00+09:00" } ]
```

- 화면 "작성기한 D-n" 은 `expiresAt` 으로 계산합니다 (연동 때 목업의 계산식을 이 값으로 바꿈).
- `optionName` · `thumbnailUrl` 은 order payload 추가 전 자격이면 `null` — 화면은 기본 이미지 · 옵션 생략.

| 경우 | 응답 |
|---|---|
| `X-User-Id` 없음 | 401 `UNAUTHORIZED` |

## 8. 내부 — admin 신고 처리 `GET /internal/reviews/{reviewId}` · `PATCH /internal/reviews/{reviewId}/blind`

호출자 admin-service(`ReviewClientAdapter`, 서킷 `review` — GET 만 재시도). 노션 확인 요청 「신고 · 리뷰 블라인드 (B안)」 4-2 계약 그대로입니다. 게이트웨이 라우트 없음, 사용자 헤더 없음.

### 8-1. 조회

```json
{ "reviewId": 31, "productId": 101, "storeId": 1, "userId": 4, "rating": 1,
  "content": "…", "imageUrls": [], "blinded": false, "blindReason": null,
  "createdAt": "2026-10-20T14:00:00+09:00" }
```

- 블라인드된 리뷰도 그대로 보입니다. `imageUrls` 는 사진 업로드(REV-03) 전이라 지금은 항상 `[]`.
- `storeId` · `userId` 는 admin 이 판매자 검토 요청의 내 스토어 여부 · 본인 신고 여부를 확인하는 데 씁니다.

### 8-2. 블라인드 · 해제

```json
{ "blinded": true, "reason": "욕설 · 비방" }
```

- `blinded=true` 면 `reason` 필수(255자 이하, 앞뒤 공백 제거). 해제는 `reason` 생략 · `null` 가능 — 저장하지 않고 `blind_reason` · `blinded_at` 을 비웁니다.
- 응답은 8-1 과 같은 모양. **같은 상태 재요청은 200 그대로**(블라인드 두 번이면 처음 사유 · 시각 유지).
- 블라인드되면 구매자 목록 · 평균 · 리뷰 수에서 빠지고, 작성자 수정 · 판매자 답글이 409 `REVIEW_BLINDED` 로 막힙니다.

| 경우 (8-1 · 8-2) | 응답 |
|---|---|
| `reviewId` 숫자 아님 · `blinded` 없음 · 형식 오류 · 블라인드인데 사유 없음 · 사유 256자 이상 | 400 `INVALID_INPUT` |
| 없는 · 삭제된 리뷰 | 404 `REVIEW_NOT_FOUND` |
