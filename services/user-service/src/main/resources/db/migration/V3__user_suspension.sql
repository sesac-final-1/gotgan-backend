-- userdb V3 — 관리자 이용정지 기간 (7일 · 30일 · 영구) + 기간 만료 자동 해제 스케줄러 잠금. 담당 perish95

ALTER TABLE users
  ADD COLUMN suspended_until DATETIME(6) NULL COMMENT '이용정지 종료 시각 — NULL 이면 영구 정지(관리자가 해제). 지나면 스케줄러가 자동 해제' AFTER suspended_at,
  ADD INDEX idx_users_status_suspended_until (status, suspended_until);  -- 자동 해제 대상: SUSPENDED · 종료 시각 지남

-- ShedLock — 레플리카가 여러 개여도 @Scheduled 작업은 한 파드만 (settlementdb 와 같은 구조)
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL COMMENT '잠금 이름 = @SchedulerLock(name)',
  lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 만료 시각',
  locked_at  TIMESTAMP(3) NOT NULL COMMENT '잠금 획득 시각',
  locked_by  VARCHAR(255) NOT NULL COMMENT '잠금을 잡은 인스턴스',
  CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ShedLock 스케줄러 잠금';
