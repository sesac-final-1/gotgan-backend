-- storedb V5 — 보완요청 서류 저장 (담당 박지연, 10/29)
-- 보완요청(store.md 7절)이 고른 서류 종류를 남겨 신청자 화면(2-2절)이 다시 낼 서류를 보여 주고, 재제출(2-3절)이 빠진 서류를 검사한다.
-- 이전에는 이벤트에만 실어 보내고 저장하지 않았다. NULL 허용 — 보완요청 중이 아니면 NULL, 이 파일 전 보완요청도 NULL(검사 생략)

ALTER TABLE store_application
  ADD COLUMN requested_docs JSON NULL COMMENT '보완요청 서류 종류 ["BUSINESS_REG", ...] — SUPPLEMENT 일 때만, 재제출 · 승인 · 반려 때 NULL' AFTER status_reason;
