-- settlementdb V1 — 담당 jihoon-devstu 가 작성한다.
-- 규칙: 컨벤션 3장 (단수형 테이블 · PK {테이블}_id · pk_/fk_/uk_/idx_ · DATETIME(6) · utf8mb4_unicode_ci · 모든 COMMENT)
-- 예시 DDL: 가이드라인 06장 (orderdb) · 07장 (paymentdb). 머지된 파일은 수정 금지 — 바꿀 때는 V2 부터.

-- ShedLock 잠금 테이블 (스케줄러 중복 실행 방지, 가이드라인 10장)
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL COMMENT '잠금 이름 = @SchedulerLock(name)',
  lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 만료 시각',
  locked_at  TIMESTAMP(3) NOT NULL COMMENT '잠금 획득 시각',
  locked_by  VARCHAR(255) NOT NULL COMMENT '잠금을 잡은 인스턴스',
  CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ShedLock 스케줄러 잠금';
