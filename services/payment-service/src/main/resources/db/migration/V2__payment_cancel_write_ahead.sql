-- paymentdb V2 — 취소 선기록(write-ahead) · 복구 스케줄러 (담당 구지훈, 10/20)
-- 포트원 취소 API 는 멱등 키가 없고, 응답이 끊기면 실제로 취소됐는지 알 수 없다.
-- 그래서 포트원을 부르기 "전에" payment_cancel 을 PENDING 으로 남기고, 결과가 확정되면 SUCCEEDED / FAILED 로 바꾼다.
-- 확정되지 않은 PENDING 은 복구 스케줄러가 포트원 결제 조회(cancellations)로 대조해 마무리한다 (docs/api/payment.md 8절).

ALTER TABLE payment_cancel
  ADD COLUMN cancellable_before BIGINT NULL
      COMMENT '취소 요청 직전 취소 가능 잔액 — 포트원 currentCancellableAmount 로 보내 같은 취소가 두 번 나가지 않게 함' AFTER cancel_amount,
  ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED'
      COMMENT 'PENDING(포트원 결과 미확정) / SUCCEEDED / FAILED — V1 행은 모두 성공 기록이라 기본값 SUCCEEDED' AFTER portone_cancellation_id,
  ADD COLUMN attempt_count INT NOT NULL DEFAULT 0
      COMMENT '포트원 취소 요청 · 대조 시도 횟수 (복구 스케줄러 경보 기준)' AFTER status,
  ADD COLUMN last_error VARCHAR(200) NULL
      COMMENT '마지막 실패 · 미확정 사유 (포트원 오류 type 등, 비밀 값 금지)' AFTER attempt_count,
  MODIFY COLUMN cancelled_at DATETIME(6) NULL
      COMMENT '포트원 취소 완료 시각 — SUCCEEDED 일 때만 값이 있음',
  ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) AFTER created_at,
  ADD INDEX idx_payment_cancel_status_updated (status, updated_at),  -- 복구 스케줄러: 오래된 PENDING 조회
  ADD INDEX idx_payment_cancel_payment_status (payment_id, status);  -- 결제별 진행 중 취소 확인 (한 결제에 PENDING 1건만)

-- ShedLock 잠금 테이블 — 복구 스케줄러를 레플리카 중 한 파드만 실행 (가이드라인 10장)
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL COMMENT '잠금 이름 = @SchedulerLock(name)',
  lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 만료 시각',
  locked_at  TIMESTAMP(3) NOT NULL COMMENT '잠금 획득 시각',
  locked_by  VARCHAR(255) NOT NULL COMMENT '잠금을 잡은 인스턴스',
  CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ShedLock 스케줄러 잠금';
