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

---

## 1. 재고 예약 — `POST /internal/stocks/reserve` (PRD-08)

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

## 2. 재고 해제 — `POST /internal/stocks/release` (PRD-08)

주문 Saga 보상(결제 실패 · 금액 불일치 · 결제 대기 만료). **"되돌릴 게 없으면 아무 일도 안 함."**

```json
{ "sagaId": "6c1f0a6e-...", "items": [ { "productOptionId": 11, "quantity": 2 }, { "productOptionId": 25, "quantity": 1 } ] }
```

`items` 는 **1번 예약 때 보낸 목록 그대로** 보냅니다. 되돌리는 수량은 product 의 RESERVE 기록 기준이고, `items` 는 예약 기록이 없을 때 표식을 남길 옵션을 알기 위해 씁니다.

| 경우 | product 처리 | 응답 |
|---|---|---|
| RESERVE 있음 · RELEASE 없음 | 예약 수량만큼 `stock + qty` + `stock_history` RELEASE | **200** `{ sagaId, items }` |
| 이미 RELEASE | 없음 | **200** (같은 응답) |
| RESERVE 없음 (예약 전에 실패했거나, 예약이 아직 처리 중) | `items` 의 옵션마다 RELEASE **수량 0** 행(해제 표식)을 남김 — 재고는 그대로 | **200** `{ sagaId, items: [] }` |

- **해제 표식이 필요한 이유**: order 는 예약 응답을 3초(읽기 타임아웃) 기다리다 실패로 보고 해제를 보냅니다. 이때 product 가 예약을 아직 처리 중이면 해제가 먼저 끝나 "되돌릴 게 없음"이 되고, 그 뒤 예약이 커밋돼 **주문은 실패했는데 재고만 줄어든 채로** 남습니다. 표식이 있으면 늦은 예약이 이를 보고 롤백합니다(1절 409 `STOCK_RESERVATION_RELEASED`).
- 구현 메모(product 내부): 예약은 RESERVE 행을 넣은 뒤 표식을, 해제는 RESERVE 행을 **잠금 읽기(`SELECT … FOR UPDATE`)** 로 확인해 두 요청이 동시에 와도 한쪽만 반영되게 합니다.
- 옵션 · 상품이 그 사이 삭제 · 판매중지돼도 해제합니다(재고 수만 돌려놓음).

## 3. 재고 복원 — `POST /internal/stocks/restore` (PRD-09)

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
    "imageUrl": "https://.../products/3/a1b2.jpg"
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
