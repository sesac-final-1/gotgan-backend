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

## 6. 판매자 리뷰 관리 — `GET /api/seller/reviews` · `GET /api/seller/reviews/summary` (REV-06)

판매자 센터 리뷰 관리(`/seller/reviews`, `SellerReviewList`). 인가 SELLER, 스토어는 게이트웨이가 넣는 `X-Store-Id` — 헤더가 없으면 403 `FORBIDDEN`(order 판매자 API 와 같음).

### 6-1. 목록 `GET /api/seller/reviews?tab=ALL&productId=&page=0&size=10`

- 내 스토어 리뷰, 삭제 제외 · **블라인드 포함**(`blinded: true` → "블라인드됨", 답글 · 관리자 검토 요청 버튼 숨김), 최신 작성순.
- `tab`: `ALL`(기본) · `NO_REPLY`(답글 없고 블라인드 아님 = 답글을 달 수 있는 것) · `LOW_RATING`(1~2점). 포토 탭은 사진 PR 에서 추가.
- `productId`: 상품 하나만 (선택). `page` 0부터 · `size` 1~50(기본 10).
- 작성자는 `writerDisplayName`(닉네임, 없으면 마스킹 실명) — 화면의 회원 목록 조회(`useSellerBuyersQuery`)는 필요 없어집니다.

```json
{ "content": [ { "reviewId": 31, "productId": 101, "productName": "린넨 셔츠", "optionName": "오트밀 / M",
    "writerDisplayName": "곳간러버", "rating": 2, "title": null, "content": "…", "fit": "LARGE", "blinded": false,
    "replyContent": null, "repliedAt": null, "createdAt": "2026-10-20T14:00:00+09:00" } ],
  "page": 0, "size": 10, "totalElements": 39, "totalPages": 4, "hasNext": true }
```

### 6-2. 평점 요약 `GET /api/seller/reviews/summary`

화면 상단(스토어 평균 · 별점 분포 · 상품별 평균)과 탭 숫자. 평균 · 분포 · 상품별은 **블라인드 · 삭제 제외**(구매자에게 보이는 리뷰 기준), 탭 숫자는 6-1 목록 조건과 같습니다.

```json
{ "averageRating": 4.62, "ratedCount": 37,
  "distribution": [ { "rating": 5, "count": 23 }, { "rating": 4, "count": 10 }, { "rating": 3, "count": 2 },
                    { "rating": 2, "count": 1 }, { "rating": 1, "count": 1 } ],
  "products": [ { "productId": 101, "productName": "린넨 셔츠", "averageRating": 4.8, "count": 12 } ],
  "tabCounts": { "all": 39, "noReply": 8, "lowRating": 3 } }
```

- 평균은 소수 둘째 자리 반올림, 리뷰가 없으면 0. 분포는 항상 5 → 1점 5개. 상품별은 평균 높은순.

| 경우 (6-1 · 6-2) | 응답 |
|---|---|
| `tab` 모르는 값 · `page` · `size` 범위 밖 · `productId` 숫자 아님 | 400 `INVALID_INPUT` |
| `X-User-Id` 없음 | 401 `UNAUTHORIZED` |
| `X-Store-Id` 없음 | 403 `FORBIDDEN` |

## 7. 판매자 답글 작성 · 수정 — `PUT /api/seller/reviews/{reviewId}/reply` (REV-06)

리뷰당 답글 1개 — 없으면 작성, 있으면 덮어씁니다(삭제 없음). 일반 텍스트 1,000자 이하, 태그는 지우고 저장.

```json
{ "content": "소중한 후기 감사합니다! 다음에도 만족하실 수 있도록 준비하겠습니다." }
```

응답 **200** `{ "reviewId": 31, "replyContent": "…(태그를 지운 값)", "repliedAt": "2026-10-28T10:00:00+09:00" }`

| 경우 | 응답 |
|---|---|
| 빈 답글 · 1,000자 초과 · 태그만 있음 | 400 `INVALID_INPUT` |
| `X-User-Id` 없음 | 401 `UNAUTHORIZED` |
| `X-Store-Id` 없음 | 403 `FORBIDDEN` |
| 없는 · 삭제된 · 다른 스토어의 리뷰 (구분하지 않음) | 404 `REVIEW_NOT_FOUND` |
| 관리자가 블라인드한 리뷰 | 409 `REVIEW_BLINDED` |

- "관리자 검토 요청" 버튼은 이 API 가 아니라 admin 신고 API(`POST /api/reports`, `reasonCode: SELLER_REQUEST`) — `admin.md` 참고.

## 8. 내부 — admin 신고 처리 · 회원 상세 `GET /internal/reviews/{reviewId}` · `PATCH /internal/reviews/{reviewId}/blind` · `GET /internal/reviews?userId=`

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

### 8-3. 회원별 리뷰 `GET /internal/reviews?userId=4&page=0&size=10`

관리자 회원 상세 "작성 리뷰"(`UserDetail`) — admin 이 `/api/admin/users/{id}` 화면에서 부릅니다. 마이페이지 `/api/reviews/me` 는 본인(`X-User-Id`)만 보는 공개 경로라 쓰지 않습니다.

```json
{ "content": [ { "reviewId": 31, "productId": 101, "productName": "린넨 셔츠", "storeId": 1, "rating": 1,
    "content": "…", "blinded": true, "blindReason": "욕설 · 비방", "createdAt": "2026-10-20T14:00:00+09:00" } ],
  "page": 0, "size": 10, "totalElements": 1, "totalPages": 1, "hasNext": false }
```

- 그 회원의 리뷰, **삭제 제외 · 블라인드 포함**, 최신 작성순. `page` 0부터 · `size` 1~50(기본 10) — 화면의 최근 5개는 `size=5`.
- 없는 회원은 빈 목록 (review 는 회원을 모름). DB 조회만 하므로 재시도 가능.
- 오류: `userId` 없음 · 숫자 아님 · `page` · `size` 범위 밖 400 `INVALID_INPUT`

## 9. 상품 상세 리뷰 (공개) — `GET /api/reviews?productId=` · `GET /api/reviews/summary?productId=` (REV-04)

상품 상세 리뷰 탭(`ShopProductReviews`). 로그인 없이 부릅니다(게이트웨이 GET 공개). **블라인드 · 삭제 리뷰는 목록 · 요약 모두에서 빠집니다.** review 는 상품을 모르므로 없는 상품은 빈 목록 · 0 입니다.

### 9-1. 목록 `GET /api/reviews?productId=101&rating=&sort=LATEST&page=0&size=10`

- `productId` 필수. `rating` 1~5 로 별점 하나만(선택 — 화면의 분포 막대 클릭).
- `sort`: `LATEST`(기본, 최신순) · `RATING_HIGH` · `RATING_LOW` — 같은 별점이면 최신 먼저. **도움순은 범위 제외**(10/28)라 없습니다.
- `page` 0부터 · `size` 1~50(기본 10).
- 작성자는 `writerDisplayName`(닉네임, 없으면 마스킹 실명)만 — **회원 ID · 아바타 · 등급은 주지 않습니다.** 화면은 기본 아바타, 등급 배지 생략.
- 포토 리뷰만 · 사진은 사진 PR(REV-03)에서 추가합니다.

```json
{ "content": [ { "reviewId": 31, "writerDisplayName": "곳간러버", "rating": 5, "title": null,
    "content": "…", "optionName": "오트밀 / M", "fit": "TRUE_TO_SIZE",
    "replyContent": "소중한 후기 감사합니다.", "repliedAt": "2026-10-21T09:30:00+09:00",
    "createdAt": "2026-10-20T14:00:00+09:00" } ],
  "page": 0, "size": 10, "totalElements": 37, "totalPages": 4, "hasNext": true }
```

### 9-2. 요약 `GET /api/reviews/summary?productId=101`

```json
{ "averageRating": 4.62, "reviewCount": 37,
  "distribution": [ { "rating": 5, "count": 23 }, { "rating": 4, "count": 10 }, { "rating": 3, "count": 2 },
                    { "rating": 2, "count": 1 }, { "rating": 1, "count": 1 } ],
  "fits": [ { "fit": "SMALL", "count": 3 }, { "fit": "TRUE_TO_SIZE", "count": 18 }, { "fit": "LARGE", "count": 2 } ] }
```

- 평균은 소수 둘째 자리 반올림(리뷰 없으면 0) — 판매자 평점 요약(6-2)과 같은 계산.
- `fits` 는 항상 3칸, 사이즈 체감을 남긴 리뷰만 셉니다. 패션 · 신발가방이 아니면 화면이 감춥니다.
- 리뷰 탭 숫자 · 상품 상단 별점은 이 값을 씁니다. 상품 카드 · 목록의 별점 · 리뷰 수(여러 상품 한 번에)는 별도 PR 입니다.

| 경우 (9-1 · 9-2) | 응답 |
|---|---|
| `productId` 없음 · 숫자 아님 · `rating` 1~5 밖 · `sort` 모르는 값 · `page` · `size` 범위 밖 | 400 `INVALID_INPUT` |

## 10. 여러 상품 · 스토어 평점 (공개) — `GET /api/reviews/ratings/products?ids=` · `GET /api/reviews/ratings/stores?ids=`

product · store 는 다른 서비스를 부르지 않으므로(`product.md` 9 · 10절 "별점 · 리뷰는 review API") 화면이 받은 ID 묶음으로 평점을 한 번에 묻습니다. 로그인 없이 부릅니다.

| 화면 | 부를 API |
|---|---|
| 상품 카드 · 상품 목록(`ProductCard` · `ShopProductList`) · 판매자 상품 목록 · 메인 | `/ratings/products?ids=` — 받은 상품 페이지의 ID 들 |
| 상품 상세 상단 · 리뷰 탭 | 9-2 `/summary?productId=` (분포까지 필요해서) |
| 스토어 홈(`ShopStoreHome`) · 관리자 스토어 목록 · 상세 | `/ratings/stores?ids=` |
| 판매자 대시보드 · 리뷰 관리 | 6-2 `/api/seller/reviews/summary` |

```json
// GET /api/reviews/ratings/products?ids=101,102
[ { "productId": 101, "averageRating": 4.62, "reviewCount": 37 },
  { "productId": 102, "averageRating": 0, "reviewCount": 0 } ]
// GET /api/reviews/ratings/stores?ids=1
[ { "storeId": 1, "averageRating": 4.71, "reviewCount": 128 } ]
```

- `ids=101,102` · `ids=101&ids=102` 둘 다 받습니다. 1~100개 (product `product-options` 내부 API 와 같은 상한).
- **요청한 순서대로**, 같은 ID 는 한 번만. **리뷰가 없으면 `0 · 0`** 으로 채워 항상 요청한 ID 수만큼 돌려줍니다.
- 블라인드 · 삭제 제외, 평균은 소수 둘째 자리 반올림 — 6-2 · 9-2 와 같은 기준.
- **평점순 · 리뷰 많은순 정렬은 이 API 로 할 수 없습니다** — 상품 목록 페이징 · 정렬은 product 가 하고 평점은 review 에 있어서입니다. `product.md` 결정(정렬은 신상품 · 가격 2종, 리뷰 · 평점 정렬은 범위 밖) 그대로이고, 화면의 "리뷰 많은순 · 평점 높은순" 메뉴는 빼야 합니다.

| 경우 | 응답 |
|---|---|
| `ids` 없음 · 숫자 아님 · 100개 초과 | 400 `INVALID_INPUT` |

## 11. 작성 자격 저장 — `OrderItemConfirmed` 수신 (REV-01)

API 가 아니라 이벤트 소비입니다. 구매확정(구매자 버튼 · 자동 구매확정 스케줄러) → order 가 `OrderItemConfirmed` 발행(SNS `order-events`) → SQS `sesac-gojigi-review-order-events`(필터: `OrderItemConfirmed` 만) → review 폴러 → `review_eligibility` 저장. 저장되면 4 · 5절 마이페이지와 1절 작성이 열립니다(비동기라 몇 초 늦을 수 있음).

| 항목 | 내용 |
|---|---|
| 큐 주소 | 환경 변수 `REVIEW_ORDER_EVENTS_QUEUE_URL`(ConfigMap) — 비어 있으면(로컬 · 테스트) 폴러를 띄우지 않음 |
| 저장 값 | orderItemId · buyerId → user_id · productId · storeId · productName · **buyerDisplayName · optionName · thumbnailUrl**(10/28 order payload 추가) · confirmedAt(KST) · 기한 = confirmedAt + 30일 |
| 멱등 | `processed_event` 와 자격을 한 트랜잭션에 — 같은 eventId 재전달은 건너뜀, 다른 eventId 로 다시 발행돼도 상품주문당 1개(uk) |
| 10/28 이전 이벤트 | `buyerDisplayName` 이 없으면 "구매자", `confirmedAt` 이 없으면 봉투 `occurredAt`, 옵션명 · 사진은 `null` |
| 실패 | DB 일시 장애는 1 → 5 → 15분 뒤 재시도, 그 밖의 오류는 기본 재전달 3회 뒤 DLQ (user · settlement 와 같은 폴러) |
| 취소 · 반품 | 구매확정 뒤에는 없으므로 자격을 지우는 이벤트는 받지 않음 |
