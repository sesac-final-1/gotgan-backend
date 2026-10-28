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
