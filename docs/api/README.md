# docs/api

서비스별 OpenAPI 명세 — `{service}.yaml` (user · store · product · order · payment · review · settlement · admin · chat).
각 서비스 `http://localhost:{port}/v3/api-docs.yaml` 에서 내보내 커밋한다. API 를 바꾸는 PR 에 함께 올린다.
에러 코드 목록은 `error-codes.md` 에 서비스별로 관리 (컨벤션 2-5).
서비스 계약 문서: `store.md` · `product.md` · `payment.md` · `settlement.md` · `admin.md`(신고 · 조치, 5절 알림 센터 `/api/notifications/**` — 10/27) · `chat.md`(1:1 채팅 chat-service :8089 — 10/27).

저장소 3분할 이후 문서 위치 (10/13 결정 1): OpenAPI 내보내기 · `error-codes.md` 는 코드와 함께 이 저장소(`gotgan-backend/docs/api/`)에 둔다.
이벤트 목록 `events.md` · 시크릿 목록 `secrets.md` · ADR 은 `gotgan-config/docs/` 에 있다 — 새 이벤트는 거기에 먼저 등록한다 (컨벤션 2-7).
