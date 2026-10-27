# chat-service API — 1:1 채팅 (CHT-01~05, 10/27 회의 추가 기능)

- 담당: 구지훈. 새 서비스 `chat-service`(:8089 · chatdb). 게이트웨이 라우트 · CI 매트릭스는 구지훈 PR · 이경석 리뷰
- 근거: 10/27 회의록 · 작업 계획(`_notion/02_내작업/2026-10-27_추가기능_작업계획_구지훈.md` 2-7절) · gotgan_guide 스펙 CHT 도메인
- 상품 문의 게시판(review — 공개 Q&A, 미확정)과 **별개인 비공개 1:1 대화**. 텍스트만
- 결정 사항은 12절 (10/27 D-1 · D-2 · D-3 확정)

## 공통

| 항목 | 규칙 |
|---|---|
| 주소 | 화면: REST `/api/chats/**` 는 Vercel rewrite(같은 출처), 실시간 `wss://api.gotgan.click/ws/chat` 은 직접 연결(게이트웨이가 이 경로만 CORS · Upgrade 허용) |
| 인가 | 게이트웨이: 로그인한 누구나(BUYER · SELLER · ADMIN). 방 단위 권한은 chat-service 가 `X-User-Id` · `X-User-Roles` · `X-Store-Id` 로 판단 — 남의 방은 **404**(있는지 알려 주지 않음) |
| 응답 | `ApiResponse<T>` 봉투, 시각은 `+09:00` |
| 저장 | chatdb `chat_room` · `chat_message` · `chat_read` (8절). 메시지는 **90일 뒤 자동 삭제** (8-1절) |
| 실시간 | STOMP over WebSocket, 파드 간 Redis Pub/Sub `chat:room:{roomId}` (6절) |
| 알림 | 받는 쪽이 그 방에 접속해 있지 않으면 admin `POST /internal/notifications`(admin.md 5절) — 같은 메시지는 한 번만(`sourceId = chat-msg-{messageId}`) |

## 방 3종류

| `type` | 화면 이름 | 참여 | 누가 여나 | 같은 방 기준 | 상태 |
|---|---|---|---|---|---|
| `STORE_INQUIRY` | 스토어 톡 | 구매자 ↔ 스토어(판매자) | **구매자만** (판매자가 먼저 못 보냄 — 스팸 방지) | 구매자 × 스토어 1개 | 없음 |
| `SELLER_SUPPORT` | 판매자 운영 상담 | 스토어(판매자) ↔ 관리자 전원 | 판매자 · 관리자 | 스토어 1개 | `OPEN` · `RESOLVED` |
| `BUYER_SUPPORT` | 고객센터 1:1 상담 | 구매자 ↔ 관리자 전원 | 구매자만 | 회원 1개 | `OPEN` · `RESOLVED` |

- 판매자 쪽은 **스토어 단위**(`X-Store-Id`) — 판매자가 바뀌어도 대화가 이어진다
- 관리자 쪽은 **관리자 전원 공용**(담당자 지정 없음). 화면에 보이는 관리자 이름은 "곳간 운영팀" 고정(D-2)
- 상담방 상태: 관리자가 `RESOLVED` 로 바꾸고, 상대가 새 메시지를 보내면 자동으로 `OPEN`
- 관리자는 **스토어 톡을 볼 수 없다** — 구매자가 스토어 톡에서 "운영팀에 도움 요청"(고객 상담을 그 방 문맥 카드로 엶)을 하면 그 스토어 톡만 읽기 전용으로 볼 수 있다

### 문맥 카드 (`context`)

다른 화면에서 방을 열면 그 화면의 대상을 **같은 방에 카드 메시지**(`kind: CONTEXT`)로 붙인다. 새 방을 만들지 않는다.

| `context.type` | 어디서 | 카드 제목 (조회 실패 시 번호만) |
|---|---|---|
| `PRODUCT` | 상품 상세 '판매자에게 문의' | product `GET /api/products/{id}` 상품명 (product 10절 구현 전엔 "상품 #id") |
| `ORDER_ITEM` | 내 주문 상세 '판매자 문의' | order 상품주문 — "주문번호 · 상품명" |
| `ORDER` | 내 주문 상세 '결제 · 환불 문의' | "주문 {orderNo}" |
| `SETTLEMENT` | 판매자 정산 상세 '이 정산 문의' | settlement — "정산 {week} · 지급액" |
| `STORE` | 스토어 정지 배너 · 관리자 스토어 상세 | "스토어 {storeName}" |
| `REPORT` | 신고 접수 안내 '처리 문의' | "신고 {대상}" |
| `CHAT_ROOM` | 스토어 톡 '운영팀에 도움 요청' | "스토어 톡 #{roomId} ({storeName})" — 관리자 읽기 전용 열람 허용 |

## 1. 방 열기 — `POST /api/chats/rooms`

```json
{ "type": "STORE_INQUIRY", "storeId": 1, "context": { "type": "PRODUCT", "id": 101 } }
```

| 경우 | 응답 |
|---|---|
| 처음 | **201** 방 (아래 모양) — 문맥이 있으면 첫 메시지로 카드 |
| 이미 있는 방 | **200** 같은 방 — 문맥이 있으면 카드만 추가(같은 문맥이 마지막 카드와 같으면 생략) |
| 판매자가 `STORE_INQUIRY` · 구매자가 `SELLER_SUPPORT` · 관리자가 `BUYER_SUPPORT` | **403** `CHAT_NOT_ALLOWED` |
| `STORE_INQUIRY` 인데 `storeId` 없음 · 관리자가 `SELLER_SUPPORT` 인데 `storeId` 없음 | **400** `INVALID_INPUT` |
| 자기 스토어에 스토어 톡 | **400** `CHAT_SELF` |

방 응답: `{ roomId, type, status(상담방만), counterpart: { name, role }, storeId, buyerId, lastMessage: { preview, at } | null, unreadCount, createdAt }`
- `counterpart.name`: 스토어 톡 = 구매자 화면엔 스토어명 · 판매자 화면엔 구매자 이름(마스킹 "김*연"), 상담방 = "곳간 운영팀" / 관리자 화면엔 스토어명 · 회원 이름

## 2. 내 방 목록 — `GET /api/chats/rooms?type=&status=`

- 최근 메시지순, 페이징 없음(사람당 수십 개)
- 보이는 방: 구매자 = 내 스토어 톡 · 내 고객 상담 / 판매자 = 내 스토어의 스토어 톡 · 내 스토어 운영 상담 / 관리자 = 상담방 2종 전체
- `status` 는 상담방에만 (관리자 상담함의 처리 대기 탭)

## 3. 메시지 — `GET /api/chats/rooms/{roomId}/messages?before=&size=30`

`[{ messageId, roomId, senderRole(BUYER · SELLER · ADMIN · SYSTEM), mine, kind(TEXT · CONTEXT · SYSTEM), content, context: { type, id, title } | null, createdAt }]`
- 최신 → 과거 순, `before`(messageId) 보다 이전 것. 재연결 뒤 놓친 메시지도 이 API 로 채운다
- 남의 방 **404** `CHAT_ROOM_NOT_FOUND`

## 4. 읽음 · 안 읽은 수

| 메서드 · 경로 | 내용 |
|---|---|
| `PATCH /api/chats/rooms/{roomId}/read` `{ lastMessageId }` | 그 메시지까지 읽음 → 204. 상대 화면의 "읽음" 표시 · 내 안 읽은 수에 반영 |
| `GET /api/chats/unread-count` | `{ count }` — 헤더 '톡' 배지. 판매자는 스토어 단위, 관리자는 상담방 전체 |

- 판매자센터 대시보드 '미답변 톡' = 마지막 메시지가 구매자 것인 스토어 톡 수 (`GET /api/chats/rooms?type=STORE_INQUIRY` 로 화면이 셈)

## 5. 상담 상태 — `PATCH /api/chats/rooms/{roomId}/status` `{ status: OPEN | RESOLVED }` (ADMIN)

- 상담방만 — 스토어 톡은 **400** `CHAT_INVALID_TYPE`
- `RESOLVED` 로 바꾸면 방에 `SYSTEM` 메시지 "상담이 종료됐어요. 더 궁금한 점이 있으면 다시 메시지를 보내 주세요"

## 6. 실시간 — WebSocket(STOMP) `wss://api.gotgan.click/ws/chat`

| 프레임 | 내용 |
|---|---|
| `CONNECT` | 헤더 `Authorization: Bearer {액세스 토큰}` — chat-service 가 Keycloak JWKS 로 검증(게이트웨이는 WebSocket Upgrade 만 통과). 실패면 `ERROR` 프레임 후 끊음 |
| `SUBSCRIBE /sub/rooms/{roomId}` | 방 멤버만(관리자 읽기 전용 열람 포함). 아니면 `ERROR` |
| `SEND /pub/rooms/{roomId}` `{ content }` | 1~1,000자 · 연속 개행 정리 · 앞뒤 공백 제거. 저장 후 구독자에게 3절 메시지 모양으로 전달. 읽기 전용 열람이면 `ERROR` |
| 서버 → 구독 | 새 메시지 · 상대 읽음 `{ type: READ, userRole, lastMessageId }` · 상태 변경 `{ type: STATUS, status }` |

- heartbeat 10초(클라이언트 · 서버) — NLB idle 350초
- 파드 간: 저장한 파드가 Redis `chat:room:{roomId}` 에 발행 → 모든 파드가 자기 구독자에게
- 연결이 끊기면 화면이 다시 연결하고 3절 `before` 없이 최신을 다시 받아 빈 곳을 채운다
- 전송 속도 제한은 게이트웨이 요청 제한(이경석)과 별개로 서비스에서 1초 5건(사용자별) — 넘으면 `ERROR` `CHAT_TOO_FAST`

## 7. 알림 연동

| 보낸 쪽 → 받는 쪽 | 알림함 받는 쪽 | 문구 | 링크 |
|---|---|---|---|
| 구매자 → 스토어 톡 | `STORE#{storeId}` | 새 톡 — "{구매자 마스킹}: 내용 앞 40자" | `/seller/chats/{roomId}` |
| 판매자 → 스토어 톡 | `USER#{buyerId}` | "{스토어명}: …" | `/mypage/chats/{roomId}` |
| 판매자 · 구매자 → 상담방 | `ADMIN` | "[판매자 상담 · 고객 상담] …" | `/admin/chats/{roomId}` |
| 관리자 → 상담방 | `STORE#` 또는 `USER#` | "곳간 운영팀: …" | 판매자 · 마이페이지 채팅 |

- 받는 쪽이 그 방을 **구독 중이면 알림을 보내지 않는다**(Redis 에 구독 중인 사용자 목록 — 연결 시 추가 · 끊김 시 제거, TTL 60초 갱신)
- admin 장애면 알림만 빠지고 메시지는 저장 · 전달된다(Resilience4j 서킷 `admin`)

## 8. DB (chatdb, Flyway V1)

```sql
CREATE TABLE chat_room (
  chat_room_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
  type            VARCHAR(20) NOT NULL,              -- STORE_INQUIRY · SELLER_SUPPORT · BUYER_SUPPORT
  buyer_id        BIGINT,                            -- STORE_INQUIRY · BUYER_SUPPORT
  store_id        BIGINT,                            -- STORE_INQUIRY · SELLER_SUPPORT
  status          VARCHAR(20),                       -- 상담방만 OPEN · RESOLVED
  last_message_id BIGINT,
  last_message_at DATETIME(6),
  created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_chat_room UNIQUE (type, buyer_id, store_id),  -- 같은 방 기준 (NULL 은 0 으로 저장해 UNIQUE 가 걸리게)
  INDEX idx_chat_room_store (store_id, last_message_at),
  INDEX idx_chat_room_buyer (buyer_id, last_message_at)
);
CREATE TABLE chat_message (
  chat_message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  chat_room_id    BIGINT NOT NULL,
  sender_id       BIGINT,                            -- SYSTEM 은 NULL
  sender_role     VARCHAR(10) NOT NULL,              -- BUYER · SELLER · ADMIN · SYSTEM
  kind            VARCHAR(10) NOT NULL,              -- TEXT · CONTEXT · SYSTEM
  content         VARCHAR(1000) NOT NULL,
  context_type    VARCHAR(20),
  context_id      BIGINT,
  context_title   VARCHAR(200),                      -- 카드 만들 때 조회한 제목 스냅샷
  created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_chat_message_room FOREIGN KEY (chat_room_id) REFERENCES chat_room (chat_room_id),
  INDEX idx_chat_message_room (chat_room_id, chat_message_id),
  INDEX idx_chat_message_created (created_at)          -- 90일 보관 삭제 (8-1절)
);
CREATE TABLE chat_read (
  chat_room_id         BIGINT NOT NULL,
  reader_key           VARCHAR(30) NOT NULL,         -- USER#{id} · STORE#{id} · ADMIN — 판매자는 스토어, 관리자는 전원 공용
  last_read_message_id BIGINT NOT NULL,
  updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (chat_room_id, reader_key)
);
```

### 8-1. 메시지 보관 — 90일 뒤 자동 삭제 (D-3)

| 항목 | 값 | 이유 |
|---|---|---|
| 대상 | `created_at` 이 90일 전보다 오래된 `chat_message` | 상담 기록 보관과 저장 공간의 균형 (10/27 결정) |
| 언제 | **하루 한 번, 09:10 KST** (`app.chat.retention.cron`) | 클러스터가 08:00 ~ 22:00 만 떠 있어 새벽 배치는 돌지 못함 — 아침 기동 · 시드가 끝난 뒤 사용자가 적은 시간 |
| 한 파드만 | ShedLock (`shedlock` 테이블, V1) | 파드가 여러 개여도 한 번만 |
| 나눠 지우기 | `DELETE … WHERE created_at < :cutoff ORDER BY chat_message_id LIMIT 1000` 를 반복, 묶음 사이 200ms 쉼 | 한 번에 큰 삭제는 테이블 잠금 · 복제 지연 · 커넥션 점유 — 1,000건씩이면 수십 ms |
| 한 번에 최대 | 50묶음(5만 건)까지, 남으면 다음 날 이어서 | 실행 시간 상한(수십 초) — 밀린 양이 많아도 하루 몫만 |
| 인덱스 | `idx_chat_message_created` | 오래된 행만 범위로 찾음(전체 스캔 없음) |
| 빈 방 | 마지막 메시지가 90일 전이고 메시지가 하나도 남지 않은 방은 `chat_read` 와 함께 삭제 — 같은 실행 끝에 1회 | 목록에 빈 방이 남지 않게 |
| 기록 | 실행마다 INFO 한 줄 (지운 메시지 · 방 수 · 걸린 시간) | Grafana 로 확인 |

- 설정: `app.chat.retention.days=90` · `batch-size=1000` · `max-batches=50` · `cron="0 10 9 * * *"` (zone Asia/Seoul)
- 시연 환경은 DB 가 매일 새로 만들어져 실제로 지울 행이 없다 — 동작은 통합 테스트(시각 고정 Clock)로 확인

## 9. 오류 코드 (접두어 `CHAT` — error-codes.md)

| 코드 | 상태 | 경우 |
|---|---|---|
| `CHAT_ROOM_NOT_FOUND` | 404 | 없거나 남의 방 |
| `CHAT_NOT_ALLOWED` | 403 | 역할이 열 수 없는 방 종류 · 읽기 전용 방에 전송 |
| `CHAT_SELF` | 400 | 자기 스토어에 스토어 톡 |
| `CHAT_INVALID_TYPE` | 400 | 스토어 톡에 상담 상태 변경 |
| `CHAT_TOO_FAST` | 429(STOMP ERROR) | 1초 5건 초과 |
| `CHAT_STORE_INACTIVE` | 409 | 정지 · 폐점 스토어에 새 스토어 톡 (D-1, store 상태 조회 API 배포 후) |

## 10. 인프라 · 배포 (구지훈)

- config: Terraform `ecr/` 저장소 `sesac-gojigi-chat`, `init-schemas.sh` chatdb · chat 계정, secrets-sync `CHAT_DB_PASSWORD`(값 등록은 사용자 — GitHub Secrets), Helm values `chat-service`(:8089), NetworkPolicy DB 접근 라벨, ConfigMap `CHAT_SERVICE_URI`
- backend: `services/chat-service`(gradle: websocket · data-jpa · flyway · data-redis · oauth2-resource-server(JWT 검증) · resilience4j), CI 매트릭스에 chat(이경석 리뷰)
- 게이트웨이: `/api/chats/**` → chat, `/ws/chat` WebSocket 라우트(Upgrade 통과 · 타임아웃 해제) + 이 경로만 CORS(Origin 확인)

## 11. 범위 밖

이미지 · 파일 전송, 단체방, 자동 응답 · 챗봇, 상담원 배정 · 운영 시간, 판매자가 먼저 거는 대화, 메시지 수정 · 삭제, 채팅 메시지 신고(악성 상대는 회원 신고 `USER` 로)

## 12. 결정 사항 (10/27)

| # | 질문 | 결정 |
|---|---|---|
| D-1 | 정지 · 폐점 스토어의 스토어 톡 | 새 방은 막고(409 `CHAT_STORE_INACTIVE`) 기존 방은 읽기만 — store 상태 조회 API(박지연) 배포 후 적용, 그 전엔 막지 않음 |
| D-2 | 관리자 쪽 표시 이름 | "곳간 운영팀" 고정 (관리자 개인 이름 노출 안 함) |
| D-3 | 메시지 보관 기간 | 90일 뒤 자동 삭제 — 하루 한 번 · 나눠 지우기 (8-1절) |
