# product-service API 계약 (설계 — 10/20)

담당 박지연. 구매자 화면(공개) · 판매자 센터(`/api/seller/products/**`) · 다른 서비스(order · store · admin)가 부르는 `/internal/**` 의 **계약**입니다. 코드가 나오면 `product.yaml`(springdoc 내보내기)을 함께 올리고, 이 문서는 호출하는 쪽이 지킬 규칙 설명으로 남깁니다.
근거: 스펙 CAT-01~03 · PRD-01~15 · ORD-04, productdb V1 · V2, 10/20 카테고리 · 판매수수료 결정(노션 "10/19 카테고리_정산 관련 내용 확인 요청" 답변).

## 공통

| 항목 | 값 |
|---|---|
| 주소 | 화면: 게이트웨이 `http://gotgan-api.duckdns.org` (HTTPS 전환 예정) — `/api/products/**` · `/api/categories/**` · `/api/seller/products/**`<br>내부: `PRODUCT_SERVICE_URI=http://product-service:8083` (ConfigMap) — `/internal/**` 는 게이트웨이 라우트 없음, 클러스터 내부 전용 |
| 응답 | `ApiResponse<T>` 봉투 (컨벤션 2-4). 오류는 `code` · `message`, `data: null` |
| 금액 | `long`, 원 단위 |
| 시각 | ISO-8601 오프셋 포함 (Asia/Seoul) |
| 인가 | 공개 GET: 로그인 없음 / 판매자: SELLER + `X-Store-Id` 헤더의 스토어 상품만 / 내부: 헤더 없음 |
| 멱등 키 | 재고 예약 · 해제 = `sagaId`, 재고 복원 = `claimId` (`stock_history` 의 `uk(type, ref_key, product_option_id)`) |
| 이미지 | DB 에는 S3 키만. 응답 URL = `IMAGES_BASE_URL` + `/` + 키(`products/...`). 첫 번째 이미지(`sort_order = 0`)가 대표 |
| 판매가 | `salePrice` = `price × (1 − discountRate/100)` 을 10원 단위 반올림 (서버 계산). 옵션 최종가 = `salePrice + extraPrice` |

### 노출 · 구매 가능 (10/20 결정)

상품 `status`(`ON_SALE` · `STOPPED` · `BLOCKED`)는 카테고리 · 스토어 상태 때문에 바꾸지 않습니다. 대신 매번 계산합니다.

| 값 | 계산 | 쓰는 곳 |
|---|---|---|
| `displayable` | `status = ON_SALE` + 하위 · 상위 카테고리 모두 `is_active` + 스토어 운영 중(`is_store_active`) + 삭제 안 됨 | 공개 목록 · 검색 · 스토어 홈에서 `false` 는 빠짐 |
| `purchasable` | `displayable` 과 같은 조건 + 옵션 삭제 안 됨 | 상세 · 장바구니 · 재고 예약 |
| 품절 | 옵션 `stock = 0` — `purchasable` 과 따로 봄 | 상세 옵션 비활성 · 재고 예약 거부 |

카테고리 · 스토어를 다시 켜면 상품은 별도 작업 없이 원래대로 보입니다.

`is_store_active` 는 `StoreStatusChanged` 의 `status` 가 **`ACTIVE` 일 때만 TRUE** 입니다. 그 밖의 값(`SUSPENDED` · `CLOSED`, 나중에 생길 수 있는 값 포함)은 모두 FALSE.

---

## 1. 재고 예약 — `POST /internal/stock/reservations` (PRD-08)

주문 Saga 3단계. 상품주문 여러 개를 **한 번에 전부 예약하거나 하나도 예약하지 않습니다.**

```json
{ "sagaId": "6c1f0a6e-...", "items": [ { "productOptionId": 11, "quantity": 2 }, { "productOptionId": 25, "quantity": 1 } ] }
```

| 경우 | product 처리 | 응답 | order 권장 처리 (결정은 이경석) |
|---|---|---|---|
| 처음 · 전부 가능 | 옵션마다 `UPDATE … SET stock = stock - :qty WHERE stock >= :qty` + `stock_history` RESERVE | **200** `{ sagaId, items }` | 다음 단계(결제 준비) |
| 같은 `sagaId` 재요청 | 없음 (이미 RESERVE 기록 있음) | **200** 처음과 같은 응답 | 다음 단계 |
| 이미 해제 표식이 있음 (order 가 타임아웃으로 포기하고 해제를 먼저 보낸 뒤 늦게 도착한 예약, 2절) | 전체 롤백 — 재고를 빼지 않음 | **409** `STOCK_RESERVATION_RELEASED` | 이미 보상을 마친 주문이라 응답을 받지 못해도 됨 (로그용) |
| 하나라도 재고 부족 | 전체 롤백 | **409** `STOCK_INSUFFICIENT` | 보상 → "재고가 부족한 상품이 있어요" |
| 하나라도 `purchasable = false` (판매중지 · 차단 · 카테고리/스토어 비활성 · 삭제된 상품/옵션) | 전체 롤백 | **409** `PRODUCT_NOT_PURCHASABLE` | 보상 → "현재 구매할 수 없는 상품이 있어요" · 결제창 안 뜸 |
| 없는 옵션 ID | 전체 롤백 | **404** `PRODUCT_OPTION_NOT_FOUND` | 보상 |
| `items` 비었음 · `quantity < 1` · 같은 옵션 ID 중복 | 없음 | **400** `INVALID_INPUT` | 보상 (order 버그) |

- 데드락을 피하려고 product 는 옵션 ID 오름차순으로 UPDATE 합니다.
- 가격은 확인하지 않습니다. 주문 금액은 order 가 Saga 1단계에서 4번 조회로 스냅샷합니다.

## 2. 재고 해제 — `POST /internal/stock/reservations/{sagaId}/release` (PRD-08)

주문 Saga 보상(결제 실패 · 금액 불일치 · 결제 대기 만료). **"되돌릴 게 없으면 아무 일도 안 함."**

```json
{ "items": [ { "productOptionId": 11, "quantity": 2 }, { "productOptionId": 25, "quantity": 1 } ] }
```

`sagaId` 는 경로에, `items` 는 **1번 예약 때 보낸 목록 그대로** 보냅니다. 되돌리는 수량은 product 의 RESERVE 기록 기준이고, `items` 는 예약 기록이 없을 때 표식을 남길 옵션을 알기 위해 씁니다.

| 경우 | product 처리 | 응답 |
|---|---|---|
| RESERVE 있음 · RELEASE 없음 | 예약 수량만큼 `stock + qty` + `stock_history` RELEASE | **200** `{ sagaId, items }` |
| 이미 RELEASE | 없음 | **200** (같은 응답) |
| RESERVE 없음 (예약 전에 실패했거나, 예약이 아직 처리 중) | `items` 의 옵션마다 RELEASE **수량 0** 행(해제 표식)을 남김 — 재고는 그대로 | **200** `{ sagaId, items: [] }` |

- **해제 표식이 필요한 이유**: order 는 예약 응답을 3초(읽기 타임아웃) 기다리다 실패로 보고 해제를 보냅니다. 이때 product 가 예약을 아직 처리 중이면 해제가 먼저 끝나 "되돌릴 게 없음"이 되고, 그 뒤 예약이 커밋돼 **주문은 실패했는데 재고만 줄어든 채로** 남습니다. 표식이 있으면 늦은 예약이 이를 보고 롤백합니다(1절 409 `STOCK_RESERVATION_RELEASED`).
- 구현 메모(product 내부): 예약은 RESERVE 행을 넣은 뒤 표식을, 해제는 RESERVE 행을 **잠금 읽기(`SELECT … FOR UPDATE`)** 로 확인해 두 요청이 동시에 와도 한쪽만 반영되게 합니다.
- 옵션 · 상품이 그 사이 삭제 · 판매중지돼도 해제합니다(재고 수만 돌려놓음).

## 3. 재고 복원 — `POST /internal/stock/restorations` (PRD-09)

취소 · 반품 Saga 단계. 클레임 1건 = 상품주문 1건 = 옵션 1개.

```json
{ "claimId": 789, "productOptionId": 11, "quantity": 1 }
```

| 경우 | product 처리 | 응답 |
|---|---|---|
| 처음 | `stock + qty` + `stock_history` RESTORE (`ref_key = claim-{claimId}`) | **200** |
| 같은 `claimId` 재요청 | 없음 | **200** |
| 옵션 · 상품이 삭제 · 판매중지됨 | 그래도 복원 (재고 수만) | **200** |
| 없는 옵션 ID | 없음 | **404** `PRODUCT_OPTION_NOT_FOUND` |
| `quantity < 1` | 없음 | **400** `INVALID_INPUT` |

- 복원 수량이 실제 판매 수량을 넘는지는 product 가 알 수 없습니다. **order 가 클레임 수량 그대로** 보냅니다.

## 4. 가격 · 구매 가능 조회 — `GET /internal/product-options?ids=11,25` (PRD-10)

장바구니 표시 · 주문서 · 주문 Saga 1단계(상품주문 스냅샷)에서 씁니다. DB 조회만 하므로 **재시도 가능(GET)**.

```json
[
  {
    "productOptionId": 11,
    "productId": 3,
    "productName": "프렌치 리넨 오버핏 셔츠",
    "optionName": "오트밀 / M",
    "storeId": 1,
    "categoryId": 301,
    "topCategoryId": 3,
    "salePrice": 75650,
    "extraPrice": 0,
    "unitPrice": 75650,
    "stock": 142,
    "purchasable": true,
    "imageUrl": "https://.../products/1/a1b2.jpg"
  }
]
```

| 경우 | 응답 |
|---|---|
| 찾은 옵션 (삭제된 상품 · 옵션 포함, 이때 `purchasable: false`) | **200** 목록에 포함 |
| 없는 ID | **200** 목록에서 빠짐 — order 는 빠진 옵션을 구매 불가로 처리 |
| `ids` 비었음 · 100개 초과 | **400** `INVALID_INPUT` |

- `optionName`: 옵션 값 1 · 2 를 ` / ` 로 이은 표시용 문자열, 옵션 없는 상품은 `null`.
- `topCategoryId`: order 가 상품주문 스냅샷에 저장 → 구매확정 때 `OrderItemConfirmed.topCategoryId` 로 settlement 에 전달(판매수수료는 상위 카테고리 기준).
- `stock` 은 표시용(장바구니 수량 상한 안내). 실제 초과 판매 방지는 1번 예약의 조건부 UPDATE.

## 5. 상품 통계 — `GET /internal/stats/products?storeId=` (PRD-11)

판매자 대시보드(store `dashboard`, STR-07) · 관리자 대시보드(admin `dashboard`, ADM-02)가 다른 서비스 통계와 **병렬로** 부릅니다. DB 조회만 하므로 재시도 가능(GET).

**세는 기준** — 삭제 안 된 `ON_SALE` 상품만, 옵션 재고 합계로 나눕니다(카테고리 · 스토어 상태는 보지 않음 — 판매자는 자기 상품 현황을 그대로 봐야 하므로).

| 구분 | 조건 |
|---|---|
| 판매중 `onSaleCount` | 재고 합계 1 이상 (재고 부족 포함) |
| 재고 부족 `lowStockCount` | 재고 합계 1~19 (`LOW_STOCK_THRESHOLD = 20`) |
| 품절 `soldOutCount` | 재고 합계 0 |

| 경우 | 응답 |
|---|---|
| `storeId` 있음 (판매자 대시보드) | **200** `{ store: { onSaleCount, lowStockCount, soldOutCount, stoppedCount, lowStockProducts[{ productId, name, stock, imageUrl }] } }` — `lowStockProducts` 는 재고 적은 순 최대 10개, `stoppedCount` 는 `STOPPED` · `BLOCKED` 합계 |
| `storeId` 없음 (관리자 대시보드) | **200** `{ platform: { onSaleCount, lowStockCount, soldOutCount, blockedCount } }` |
| 상품 없는 스토어 | **200** 모두 0, `lowStockProducts: []` |

- 응답 모양은 settlement `/internal/stats/settlements` 와 같게 `store` / `platform` 중 하나만 채웁니다.

## 6. 관리자 상품 조회 · 강제 판매중지 (PRD-13)

admin 이 스토어 상세 화면에서 상품을 찾아 차단합니다. 차단 · 해제 알림은 없고, 판매자는 판매자 상품 목록에서 상태와 사유를 봅니다.

### 6-1. 스토어 상품 목록 — `GET /internal/products?storeId=`

판매자 상품 목록(11절)과 같은 응답을 스토어를 지정해서 씁니다. **전체 조회**(페이징 없음 — 화면이 검색 · 정렬 · 페이징, 프론트 컨벤션 4-7). 삭제된 상품은 빠지고, 상태와 상관없이 모두 보입니다. `storeId` 없으면 **400** `INVALID_INPUT`.

### 6-2. 강제 판매중지 · 해제 — `PATCH /internal/products/{productId}/block`

```json
{ "blocked": true, "reason": "상품 정보 고시 누락 — 원산지 표기 필요" }
```

| 경우 | product 처리 | 응답 |
|---|---|---|
| `blocked: true` | `status = BLOCKED` · `status_reason = reason` (이미 BLOCKED 면 사유만 바꿈) | **200** `{ productId, status, statusReason }` |
| `blocked: false` · 지금 BLOCKED | `status = STOPPED` · 사유 지움 → 판매자가 점검 후 직접 재개 | **200** |
| `blocked: false` · BLOCKED 아님 | 없음 | **200** (현재 상태) |
| `blocked: true` 인데 `reason` 비었음 | 없음 | **400** `PRODUCT_BLOCK_REASON_REQUIRED` |
| 없거나 삭제된 상품 | 없음 | **404** `PRODUCT_NOT_FOUND` |

- BLOCKED 상품은 판매자가 **재개할 수 없습니다**(15절, `PRODUCT_BLOCKED`). 사유를 고치도록 **수정 · 삭제는 가능**하고, 고친 뒤 관리자가 해제합니다.
- settlement `PATCH /internal/settlements/{id}/hold {hold, reason}` 와 같은 모양입니다.

## 7. 카테고리 관리 — `/internal/categories` (CAT-02 · 10/20 결정)

admin `category` 패키지가 부릅니다. 카테고리는 **삭제하지 않습니다**(store 희망 카테고리 · settlement 수수료가 `category_id` 를 논리 참조) — 사용 중지만 합니다.

| API | 요청 | 응답 |
|---|---|---|
| `GET /internal/categories` | | **200** 트리 — 비활성 포함 `[{ id, name, nameEn, sortOrder, active, children[{ id, name, nameEn, sortOrder, active }] }]` |
| `POST /internal/categories` | `{ parentId?, name, nameEn?, sortOrder? }` | **201** 만든 카테고리 |
| `PUT /internal/categories/{id}` | `{ name, nameEn?, sortOrder }` | **200** — 상위 · 하위 이동(`parentId` 변경)은 안 됨 |
| `PATCH /internal/categories/{id}` | `{ active }` | **200** — 사용 / 사용 중지 |

**생성 규칙**

| 경우 | 처리 | 응답 |
|---|---|---|
| 상위 (`parentId` 없음) | **`active = false` 로 생성** → 수수료 등록 뒤 PATCH 로 켬 | **201** |
| 하위 (`parentId` = 상위) | `active = true` 로 생성 (상위가 꺼져 있으면 함께 숨겨짐) | **201** |
| `parentId` 가 하위 카테고리 (3단계) | 없음 | **400** `CATEGORY_DEPTH_EXCEEDED` |
| 없는 `parentId` | 없음 | **404** `CATEGORY_NOT_FOUND` |
| 같은 부모 아래 같은 이름 (생성 · 수정) | 없음 | **409** `CATEGORY_NAME_DUPLICATED` |
| `sortOrder` 생략 | 같은 부모 안 맨 뒤 | — |

**사용 / 사용 중지 (PATCH)**

| 경우 | 처리 | 응답 |
|---|---|---|
| 값이 바뀜 | `is_active` 변경 — 그 아래 상품의 `status` 는 그대로, `displayable` · `purchasable` 만 계산으로 바뀜 | **200** |
| 같은 값 | 없음 | **200** (멱등) |
| 없는 카테고리 | 없음 | **404** `CATEGORY_NOT_FOUND` |

- **상위를 켜기 전 수수료율 확인은 admin 이 합니다**(settlement 현재 율 조회 → 없으면 켜지 않음). product 는 다른 서비스를 부르지 않습니다(CAT-03).
- 하위를 켜도 상위가 꺼져 있으면 여전히 숨겨집니다.
- 시드 카테고리 ID(1 식품 · 2 리빙 · 3 패션 · 4 신발가방 · 5 뷰티 · 6 디지털, 하위 = 상위 × 100 + 순번)는 맨 아래 "설계 정리"에 둡니다.

## 8. 카테고리 트리 — `GET /api/categories` (CAT-01)

공개. 쇼핑몰 탐색 · 판매자 상품 등록의 카테고리 선택 · 입점 신청 희망 카테고리가 **같은 API** 를 씁니다.

```json
[
  { "id": 3, "name": "패션의류", "nameEn": "Apparel",
    "children": [ { "id": 301, "name": "셔츠", "nameEn": null }, { "id": 302, "name": "팬츠", "nameEn": null } ] }
]
```

- **사용 중인 것만**: 꺼진 상위는 하위까지 통째로 빠지고, 꺼진 하위는 그 하위만 빠집니다.
- 상위 · 하위 모두 `sortOrder` 순으로 정렬해서 줍니다(응답에 `sortOrder` 는 넣지 않음).
- 판매수수료율은 넣지 않습니다(settlement 소관, 관리자 화면은 admin 이 조합).

## 9. 상품 목록 · 검색 — `GET /api/products` (PRD-06 · PRD-14 · STR-12)

공개. 목록 · 검색 · 메인 화면 · 스토어 홈이 모두 이 API 를 씁니다. **`displayable = true` 인 상품만** 나옵니다.

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `categoryId` | — | 상위 ID 면 그 아래 하위 전부, 하위 ID 면 그 하위만 |
| `storeId` | — | 스토어 홈 |
| `q` | — | 상품명 · 하위 카테고리 이름에 포함(LIKE). 앞뒤 공백 제거 후 1~50자 |
| `minPrice` · `maxPrice` | — | `salePrice` 기준, `minPrice ≤ salePrice < maxPrice` (화면 가격대 버튼과 같은 경계) |
| `excludeSoldOut` | `false` | `true` 면 품절 상품 제외 |
| `sort` | `NEW` | `NEW`(신상품) · `PRICE_ASC`(낮은 가격) · `PRICE_DESC`(높은 가격). 같으면 `productId` 큰 순 — 페이지가 바뀌어도 순서가 흔들리지 않게 |
| `page` · `size` | `0` · `20` | `page` 는 0부터, `size` 최대 100 |

응답 `PageResponse<ProductSummary>`:

```json
{ "productId": 3, "name": "프렌치 리넨 오버핏 셔츠", "storeId": 1, "categoryId": 301, "topCategoryId": 3,
  "price": 89000, "discountRate": 15.00, "salePrice": 75650,
  "imageUrl": "https://.../products/1/a1b2.jpg", "soldOut": false, "createdAt": "2026-10-20T10:00:00+09:00" }
```

| 경우 | 응답 |
|---|---|
| 조건에 맞는 상품 없음 · 없거나 꺼진 카테고리 · 운영 안 하는 스토어 | **200** 빈 페이지 |
| `minPrice > maxPrice` · 음수 · 모르는 `sort` · `size > 100` · `q` 50자 초과 | **400** `INVALID_INPUT` |

- `soldOut`: 삭제 안 된 옵션의 재고가 모두 0.
- **메인 화면(PRD-14)**: 카테고리마다 `?categoryId={상위}&sort=NEW&size=8`. 판매량 랭킹 · 리뷰순 · 평점순은 범위 밖입니다(판매량은 order, 리뷰는 review 데이터 — product 는 다른 서비스를 부르지 않음).
- 카드에 별점 · 리뷰 수는 없습니다. 스토어 이름은 화면이 스토어 목록(`GET /api/stores`)으로 붙입니다.

## 10. 상품 상세 — `GET /api/products/{productId}` (PRD-07)

공개.

```json
{
  "productId": 3, "storeId": 1,
  "categoryId": 301, "categoryName": "셔츠", "topCategoryId": 3, "topCategoryName": "패션의류",
  "name": "프렌치 리넨 오버핏 셔츠",
  "price": 89000, "discountRate": 15.00, "salePrice": 75650,
  "description": "<p>…</p>", "material": "리넨 100%", "origin": "대한민국",
  "images": [ "https://.../products/1/a1b2.jpg", "https://.../products/1/c3d4.jpg" ],
  "option1Name": "컬러", "option2Name": "사이즈",
  "options": [
    { "productOptionId": 11, "option1Value": "오트밀", "option2Value": "M", "extraPrice": 0, "soldOut": false },
    { "productOptionId": 12, "option1Value": "오트밀", "option2Value": "L", "extraPrice": 2000, "soldOut": true }
  ],
  "purchasable": true, "soldOut": false,
  "createdAt": "2026-10-20T10:00:00+09:00"
}
```

| 경우 | 응답 | 화면 |
|---|---|---|
| 판매 중 | **200** `purchasable: true` | 품절 옵션만 비활성 |
| 판매중지 · 강제 판매중지 · 카테고리 꺼짐 · 스토어 정지/폐점 | **200** `purchasable: false` | "현재 구매할 수 없는 상품" · 담기 · 구매 버튼 비활성 |
| 없거나 삭제된 상품 | **404** `PRODUCT_NOT_FOUND` | 없는 상품 안내 |

- `images`: 첫 번째가 대표, 최대 5장.
- `options`: 삭제된 조합은 빠집니다. **옵션 없는 상품도 옵션 1개**(`option1Value · option2Value = null`)가 있어서, 장바구니는 언제나 `productOptionId` 로 담습니다.
- 재고 수는 공개하지 않고 `soldOut` 만 줍니다. 담을 때 재고를 넘으면 주문 단계(1절)에서 `STOCK_INSUFFICIENT`.
- 스토어 정보(이름 · 배송비)는 화면이 `GET /api/stores/{storeId}` 를, 별점 · 리뷰는 review API 를 따로 부릅니다.

## 11. 판매자 상품 목록 — `GET /api/seller/products` (PRD-04)

SELLER. `X-Store-Id` 스토어의 상품을 **전체 조회**합니다(페이징 · 필터 없음 — 화면이 상태별 개수 · 검색 · 정렬 · 페이징, 프론트 컨벤션 4-7). 삭제된 상품만 빠지고 상태와 상관없이 모두 보입니다. 등록일 최신 순.

```json
[
  { "productId": 3, "name": "프렌치 리넨 오버핏 셔츠",
    "categoryId": 301, "categoryName": "셔츠", "topCategoryName": "패션의류",
    "price": 89000, "discountRate": 15.00, "salePrice": 75650,
    "imageUrl": "https://.../products/1/a1b2.jpg", "totalStock": 142, "soldOut": false,
    "status": "ON_SALE", "statusReason": null,
    "displayable": true, "hiddenReason": null,
    "createdAt": "2026-10-20T10:00:00+09:00" }
]
```

- `hiddenReason`: 쇼핑몰에 안 보이는 이유 — `STOPPED` · `BLOCKED` · `CATEGORY_INACTIVE` · `STORE_INACTIVE` 중 하나, 보이면 `null`. 화면은 "쇼핑몰 미노출" 표시와 이유를 보여 줍니다.
- 화면 상태 탭(판매중 · 품절 · 판매중지)은 `status` 와 `soldOut` 으로 셉니다. `BLOCKED` 는 판매중지 탭에 사유와 함께.

## 12. 판매자 상품 상세 — `GET /api/seller/products/{productId}`

수정 화면용. 10절 응답에 판매자만 보는 값을 더합니다: `status` · `statusReason` · `hiddenReason`, 옵션별 `stock`, 이미지 `[{ key, url }]`(13절 수정 때 `imageKeys` 로 그대로 돌려보냄).

| 경우 | 응답 |
|---|---|
| 내 스토어 상품 | **200** |
| 다른 스토어 상품 · 없거나 삭제된 상품 | **404** `PRODUCT_NOT_FOUND` — 다른 스토어 상품이 있는지도 알려 주지 않음 |

판매자 API(11~17절) 공통: 역할 확인은 **게이트웨이**가 합니다(`/api/seller/**` = SELLER — 토큰 없으면 401 `UNAUTHORIZED`, SELLER 가 아니면 403 `FORBIDDEN`, 같은 봉투). product 는 **소유권**만 봅니다 — `X-Store-Id` 스토어의 상품이 아니면 **404** `PRODUCT_NOT_FOUND`, 헤더가 없으면 **403** `FORBIDDEN`.

## 13. 상품 등록 · 수정 — `POST /api/seller/products` · `PUT /api/seller/products/{productId}` (PRD-01 · 02 · 03)

옵션 · 이미지를 포함해 **통째로** 보냅니다. **수정(PUT)은 재고 · 판매 상태를 바꾸지 않습니다** — 재고는 14절, 상태는 15절.

```json
{
  "categoryId": 301,
  "name": "프렌치 리넨 오버핏 셔츠",
  "price": 89000, "discountRate": 15,
  "description": "<p>…</p>", "material": "리넨 100%", "origin": "대한민국",
  "option1Name": "컬러", "option2Name": "사이즈",
  "options": [
    { "productOptionId": null, "option1Value": "오트밀", "option2Value": "M", "extraPrice": 0, "stock": 30 }
  ],
  "imageKeys": [ "products/1/a1b2.jpg", "products/1/c3d4.jpg" ],
  "status": "ON_SALE"
}
```

**입력 규칙**

| 항목 | 규칙 | 어기면 |
|---|---|---|
| `categoryId` | 사용 중인 **하위** 카테고리 (상위가 꺼져 있어도 안 됨) — product 자기 DB 로 검증(CAT-03) | **400** `PRODUCT_CATEGORY_INVALID` |
| `name` · `material` · `origin` | 1~100자 · 1~100자 · 1~50자 | **400** `INVALID_INPUT` |
| `price` · `discountRate` | 100원 이상 · 0~90 정수(%) — `salePrice` 는 서버 계산 | **400** `INVALID_INPUT` |
| `description` | HTML. 서버가 sanitizer 로 위험한 태그 · 속성(script · on* 등)을 지우고 저장 | — |
| 옵션 그룹 | 0~2개. **없으면 `options` 에 값이 `null` 인 1개만** | **400** `PRODUCT_OPTION_INVALID` |
| `options` | 1~100개 · 조합 중복 없음 · 옵션 그룹 수와 값 개수가 맞음 · `extraPrice ≥ 0` · `stock ≥ 0` | **400** `PRODUCT_OPTION_INVALID` |
| `imageKeys` | 1~5개, 순서대로 저장(첫 번째가 대표) · 17절에서 받은 **내 스토어 키**(`products/{storeId}/`)만 | **400** `PRODUCT_IMAGE_INVALID` |
| `status` | 등록 때만 `ON_SALE`(기본) · `STOPPED`. 수정 때는 무시 | **400** `INVALID_INPUT` |

**등록 (POST)** → **201** 12절 응답. 화면의 "상품 복사"는 12절로 읽은 내용을 `status: STOPPED` 로 다시 등록하면 됩니다(이미지 키 재사용 가능).

**수정 (PUT)** — 옵션은 `productOptionId` 로 맞춥니다.

| 요청의 옵션 | 처리 |
|---|---|
| `productOptionId` 있음 | 그 조합 유지, `extraPrice` 만 바꿈. `stock` 은 무시. 값(`option1Value` · `option2Value`)이 기존과 다르면 **400** `PRODUCT_OPTION_INVALID` |
| `productOptionId` 없음 | 새 조합 생성, `stock` 으로 시작 |
| 기존에 있었는데 요청에 없음 | **소프트 삭제**(`deleted_at`) — 지난 주문 · 장바구니 · 재고 이력이 옵션 ID 를 참조 |

| 경우 | 응답 |
|---|---|
| 정상 | **200** 12절 응답 |
| `BLOCKED` 상품 | 수정 가능 (사유를 고치는 용도) — 상태는 그대로 BLOCKED |
| 이미지 | 요청 순서대로 다시 저장, 빠진 이미지는 행 삭제 (S3 객체는 남음 — 삭제 권한 없음) |

- 화면 정리(내 담당 `features/product`): 옵션 최대 3개 → 2개, 이미지 "추가 최대 9장" → 최대 5장, 상품정보 제공고시는 소재 · 제조국만(나머지는 상세 설명에), 상품별 배송비 · 출고 소요일 · 엑셀 일괄등록 버튼 제거(배송 정책은 스토어 단위, STR-06). 조합표의 "판매" 체크를 푼 조합은 `options` 에서 뺍니다.

## 14. 재고 수정 — `PATCH /api/seller/products/{productId}/stocks` (PRD-02)

판매자가 **화면에서 본 재고(`seenStock`)** 를 같이 보냅니다. 그 사이 주문으로 재고가 바뀌었으면 덮어쓰지 않습니다.

```json
{ "items": [ { "productOptionId": 11, "stock": 50, "seenStock": 42 } ] }
```

| 경우 | product 처리 | 응답 | 화면 |
|---|---|---|---|
| 모두 `현재 재고 = seenStock` | `UPDATE … SET stock = :stock WHERE product_option_id = :id AND stock = :seen` | **200** `{ options[{ productOptionId, stock }] }` | 저장 완료 |
| 하나라도 재고가 그 사이 바뀜 | 전체 롤백 | **409** `STOCK_CHANGED` | "그 사이 주문이 들어와 재고가 바뀌었어요" → 12절 다시 읽고 재입력 |
| 이 상품의 옵션이 아니거나 삭제된 옵션 | 전체 롤백 | **404** `PRODUCT_OPTION_NOT_FOUND` | |
| `stock < 0` · `items` 비었음 | 없음 | **400** `INVALID_INPUT` | |

- 재고 0 이면 자동으로 품절, 다시 채우면 판매 — `status` 는 바꾸지 않습니다.
- `BLOCKED` 상품도 재고 수정은 가능합니다.

## 15. 판매중지 · 재개 (일괄) — `PATCH /api/seller/products/status` (PRD-05 · PRD-15)

한 개든 여러 개든 같은 API. **건마다 따로 처리**하고 결과를 나눠 줍니다(settlement 일괄 지급과 같은 모양).

```json
{ "productIds": [3, 4, 7], "status": "STOPPED" }
```

**200** `{ "success": [3, 4], "failed": [ { "productId": 7, "code": "PRODUCT_BLOCKED", "message": "관리자가 판매중지한 상품은 재개할 수 없습니다." } ] }`

| 경우 | 결과 |
|---|---|
| `ON_SALE` ↔ `STOPPED` | `success` |
| 이미 그 상태 | `success` (멱등) |
| `BLOCKED` 상품 | `failed` · `PRODUCT_BLOCKED` |
| 없음 · 삭제됨 · 다른 스토어 상품 | `failed` · `PRODUCT_NOT_FOUND` |
| `status` 가 `ON_SALE` · `STOPPED` 가 아님 · `productIds` 비었음 · 100개 초과 | **400** `INVALID_INPUT` (아무것도 처리 안 함) |

- 재고 0 인 상품도 재개할 수 있습니다(품절로 보임).

## 16. 상품 삭제 — `DELETE /api/seller/products/{productId}` (PRD-05)

| 경우 | 처리 | 응답 |
|---|---|---|
| 내 스토어 상품 (상태 무관, BLOCKED 포함) | **소프트 삭제**(`product.deleted_at`) — 옵션 · 재고 이력은 그대로 | **200** |
| 없음 · 이미 삭제 · 다른 스토어 상품 | 없음 | **404** `PRODUCT_NOT_FOUND` |

- 지난 주문은 스냅샷이라 영향 없음. 장바구니에 담긴 옵션은 4절에서 `purchasable: false` 가 됩니다.
- 화면의 일괄 삭제는 선택한 개수만큼 이 API 를 부릅니다(데모 규모).

## 17. 이미지 업로드 주소 발급 — `POST /api/seller/products/images/presign` (PRD-03)

브라우저가 S3 에 **직접** 올립니다(서버를 거치지 않음).

```json
{ "files": [ { "contentType": "image/jpeg", "size": 834512 } ] }
```

**200** `[ { "key": "products/1/7f3c9e2a.jpg", "uploadUrl": "https://sesac-gojigi-images-4380.s3...", "expiresAt": "2026-10-20T10:10:00+09:00" } ]`

| 경우 | 응답 |
|---|---|
| 1~5개 · `image/jpeg` · `image/png` · `image/webp` · 각 10MB 이하 | **200** — 키는 `products/{storeId}/{UUID}.{확장자}`, 주소 유효 10분 |
| 개수 · 형식 · 크기 위반 | **400** `PRODUCT_IMAGE_INVALID` |

**업로드 순서**: ① 이 API → ② 브라우저가 `uploadUrl` 로 `PUT`(요청 `Content-Type` 을 발급 때와 같게) → ③ 13절 `imageKeys` 에 키를 넣어 저장.

- 버킷 `sesac-gojigi-images-4380` 의 `products/` 는 공개 읽기, 쓰기는 presigned PUT 만. CORS(PUT · HEAD, vercel · `localhost:5173`)는 설정돼 있습니다.
- 올리고 저장하지 않은 파일은 S3 에 남습니다(삭제 권한 없음 — 학습 프로젝트라 보류).
- 권장 크기 1000×1250px 이상은 화면 안내만, 서버는 검사하지 않습니다.

## 18. 오류 코드 (`docs/api/error-codes.md`)

도메인 코드는 product-service 안 도메인별 enum(`{domain}/exception/{Domain}ErrorCode`), 공통 코드는 `libs/common-web` 의 `CommonErrorCode` 입니다. 옵션 · 이미지 코드도 접두어가 `PRODUCT` 라 `ProductErrorCode` 에 둡니다.

| 코드 | HTTP | enum | 언제 |
|---|---|---|---|
| `CATEGORY_NOT_FOUND` | 404 | `CategoryErrorCode` | 없는 카테고리 (관리 · 생성 때 `parentId`) |
| `CATEGORY_NAME_DUPLICATED` | 409 | `CategoryErrorCode` | 같은 부모 아래 같은 이름 |
| `CATEGORY_DEPTH_EXCEEDED` | 400 | `CategoryErrorCode` | 하위 아래에 생성 (3단계) |
| `PRODUCT_NOT_FOUND` | 404 | `ProductErrorCode` | 없거나 삭제된 상품 · 다른 스토어 상품 |
| `PRODUCT_OPTION_NOT_FOUND` | 404 | `ProductErrorCode` | 없는 옵션 ID (재고 예약 · 복원) · 이 상품의 옵션이 아님 (재고 수정) |
| `PRODUCT_NOT_PURCHASABLE` | 409 | `ProductErrorCode` | 재고 예약 때 판매중지 · 차단 · 카테고리/스토어 비활성 · 삭제된 상품/옵션 |
| `PRODUCT_BLOCKED` | 409 | `ProductErrorCode` | 강제 판매중지 상품을 판매자가 재개 |
| `PRODUCT_BLOCK_REASON_REQUIRED` | 400 | `ProductErrorCode` | 강제 판매중지에 사유 없음 |
| `PRODUCT_CATEGORY_INVALID` | 400 | `ProductErrorCode` | 등록 · 수정 때 상위 · 사용 중지 · 없는 카테고리 |
| `PRODUCT_OPTION_INVALID` | 400 | `ProductErrorCode` | 옵션 그룹 · 조합 규칙 위반, 기존 조합의 값 변경 |
| `PRODUCT_IMAGE_INVALID` | 400 | `ProductErrorCode` | 이미지 개수 · 형식 · 크기, 내 스토어 키가 아님 |
| `STOCK_INSUFFICIENT` | 409 | `StockErrorCode` | 재고 예약 때 재고 부족 |
| `STOCK_RESERVATION_RELEASED` | 409 | `StockErrorCode` | 해제 표식이 있는 `sagaId` 로 늦게 도착한 예약 |
| `STOCK_CHANGED` | 409 | `StockErrorCode` | 판매자 재고 수정 때 그 사이 재고가 바뀜 |
| `INVALID_INPUT` · `FORBIDDEN` (공통) | 400 · 403 | `CommonErrorCode` | 형식 오류 · 판매자 헤더 없음 |

호출하는 서비스는 409 · 404 · 400 을 자기 도메인 코드로 바꾸거나 그대로 전달하고, **서킷 실패로 세지 않습니다**(`ignore-exceptions` = `BusinessException`).

## 19. 호출 쪽 준비 사항

**order (이경석)**
- 서킷 브레이커 인스턴스 이름은 **`product`**(대상 서비스 이름). 재시도는 4절(GET)만. 1~3절은 POST 지만 멱등 키(`sagaId` · `claimId`)가 있어 복구 스케줄러가 같은 키로 다시 불러도 됩니다.
- **해제(2절) 요청에 예약 때 보낸 `items` 를 그대로 담아 주세요** — 늦게 도착한 예약을 막는 표식에 씁니다.
- 예약 409: `STOCK_INSUFFICIENT` · `PRODUCT_NOT_PURCHASABLE` → 보상 + 화면 안내, `STOCK_RESERVATION_RELEASED` 는 이미 보상한 주문이라 무시.
- 4절 응답의 `topCategoryId` 를 상품주문 스냅샷에 저장 → `OrderItemConfirmed.topCategoryId`.
- 제안: 장바구니 표시 · 담기에서 4절 `purchasable` 사용, 4절 응답에서 빠진 옵션 ID 는 구매 불가로 처리.

**store (박지연)**: 판매자 대시보드에서 5절을 `storeId` 로 호출. 타임아웃이면 상품 칸만 비우고 부분 응답(STR-07).

**admin (구지훈)**
- 관리자 대시보드 5절(`storeId` 없이), 스토어 상세 6-1 · 강제 판매중지 6-2, 카테고리 관리 7절.
- **상위 카테고리를 켜기 전(7절 PATCH `active: true`)에 settlement 현재 수수료율을 확인**하고, 없으면 켜지 않음(10/20 결정 A안).
- product 오류의 code · message · HTTP 상태를 그대로 전달.

**frontend (박지연, `features/product` · `features/store`)**
- 응답은 컨벤션대로(숫자 ID · 영문 상태값), 목업 모양으로 바꾸는 건 `productApi.js` 안에서: 카테고리 문자열 ID → 숫자, `status` 영문 → 한글, `discount` 0.15 ↔ `discountRate` 15, 페이지 번호 0부터.
- 스토어 정보는 `GET /api/stores/{id}`, 별점 · 리뷰는 review API 를 따로 호출. 상세 `purchasable: false` 면 버튼 비활성.
- 화면 정리 목록은 13절 끝.

## 10/20 설계 정리

| # | 항목 | 정한 내용 |
|---|---|---|
| 1 | 상품 상세의 스토어 정보 | 화면이 store 를 따로 호출 — product 는 다른 서비스를 부르지 않음(CAT-03) |
| 2 | 판매자 재고 수정 | PUT 과 분리한 PATCH + `seenStock` 비교 (DDL 주석 "본 값 비교") |
| 3 | 목업과 응답 모양 차이 | 서버는 컨벤션, 매핑은 프론트 api 함수(내 담당) |
| 4 | 이미지 최대 장수 | 5장 — 10/15 ERD 결정 4-12(스펙 우선). 화면 문구 "추가 9장"은 고칠 대상 |
| 5 | 타임아웃 뒤 늦게 도착한 예약 | 해제 요청에 `items`, 예약 기록이 없으면 수량 0 해제 표식 → 늦은 예약 409 |
| 6 | 재고 API 경로 | 컨벤션 2-4 예시 `/internal/stock/reservations`(동사 경로 대신 자원 경로) |
| 7 | 정렬 · 랭킹 | 신상품 · 가격 2종만. 판매량 · 리뷰 · 평점 정렬과 메인 랭킹은 범위 밖 |
| 8 | 검색 범위 | 상품명 + 하위 카테고리 이름 (스토어 이름 제외) |
| 9 | 판매자 · 관리자 상품 목록 | 전체 조회 — 프론트 컨벤션 4-7 (스펙 PRD-04 "서버 페이징"보다 나중 결정) |
| 10 | 상품정보 제공고시 | 소재 · 제조국만 저장(10/19 V2), 나머지는 상세 설명 |
| 11 | 카테고리 이동 | 수정에서 `parentId` 변경 불가 — 수수료 기준이 조용히 바뀜 |
| 12 | 강제 판매중지 상품 | 재개만 막고 수정 · 재고 · 삭제는 허용 |
| 13 | 노출 · 구매 판단 | `status` 는 그대로 두고 `displayable` · `purchasable` 계산(10/20 카테고리 결정) |
| 14 | 옵션 없는 상품 | 값이 `null` 인 옵션 1개 — 장바구니 · 주문은 언제나 옵션 ID |
| 15 | 공개 상세의 재고 수 | 비공개, `soldOut` 만 |
| 16 | 스토어 운영 중 | `StoreStatusChanged.status = ACTIVE` 일 때만 (값이 늘어도 자동으로 숨김) |

**스펙 반영 필요 (가이드 PR, 박지연)**: CAT-02 상위 비활성 생성 · PRD-04 전체 조회 · PRD-06 정렬 3종 · 검색 범위 · PRD-06~08 노출 · 구매 규칙 · PRD-10 응답에 `topCategoryId` · `imageUrl` · `stock` · PRD-13 관리자 상품 목록(6-1).

### 시드 카테고리 ID (10/20 확정 — product 시드는 6주차, settlement 수수료 시드도 같은 ID)

| category_id | 상위 | name_en | sort_order | 판매수수료율 | 하위 |
|---|---|---|---|---|---|
| 1 | 식품 | Food | 4 | 7% | 101 과일 · 102 꿀·잼 · 103 쌀·잡곡 · 104 커피 |
| 2 | 리빙·인테리어 | Living | 3 | 9% | 201 식기 · 202 화병 · 203 조명 · 204 침구 · 205 수납 · 206 캔들 |
| 3 | 패션의류 | Apparel | 1 | 11% | 301 셔츠 · 302 팬츠 · 303 원피스 · 304 니트 · 305 아우터 · 306 티셔츠 |
| 4 | 신발·가방 | Shoes & Bags | 2 | 11% | 401 로퍼 · 402 스니커즈 · 403 토트백 · 404 백팩 · 405 크로스백 |
| 5 | 뷰티 | Beauty | 5 | 10% | 501 스킨케어 · 502 바디·핸드 · 503 립 · 504 선케어 |
| 6 | 디지털·가전 | Digital | 6 | 6% | 601 키보드 · 602 음향 · 603 데스크 |

하위 ID = 상위 × 100 + 순번. 시드 카테고리는 모두 사용 중. store 시드의 store 1 = 1(식품) · store 2 = 2(리빙).
