-- orderdb V6 — 아웃박스 발행 시도 기록 (담당 이경석, 10/26)
-- 커밋 직후 바로 발행하고, 실패한 행은 발행기(ShedLock)가 다시 보낸다 — settlement event_outbox(V7)와 같은 방식.
-- 시도 횟수가 한도를 넘으면 ERROR 로그(수동 확인). 발행기는 (status, outbox_event_id) 인덱스로 오래된 NEW 부터 읽는다.

ALTER TABLE outbox_event
  ADD COLUMN attempt_count INT NOT NULL DEFAULT 0
      COMMENT '발행 시도 횟수 (경보 기준)' AFTER status,
  ADD COLUMN last_error VARCHAR(200) NULL
      COMMENT '마지막 발행 실패 사유' AFTER attempt_count;
