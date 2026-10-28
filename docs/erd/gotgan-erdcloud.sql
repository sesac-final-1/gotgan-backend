-- 곳간(GOTGAN) 전체 ERD — ERD Cloud 가져오기용 (자동 생성: node docs/erd/build-erd.mjs, 직접 수정 금지)
-- 생성: 2026-10-28 · 원본: services/*-service/src/main/resources/db/migration (Flyway)
-- 서비스마다 DB 가 따로라 서비스 사이 참조(user_id · order_item_id 등 "논리 참조")는 FK 로 그리지 않는다 — 컬럼 COMMENT 참고
-- 제외: shedlock (스케줄러 잠금). 여러 DB 에 있는 같은 이름 테이블은 {db}_ 접두어

-- ============================================================ userdb (user-service · 담당 이경석)
-- 원본: V1__init.sql · V2__init_userdb.sql · V3__user_suspension.sql · V4__point_use_saga.sql

-- 회원 프로필 · 포인트 잔액 · 등급 (계정은 Keycloak)
CREATE TABLE `users` (
	`user_id`	BIGINT	NOT NULL,
	`keycloak_id`	VARCHAR(36)	NULL	COMMENT 'Keycloak 사용자 ID(UUID). 자체 가입: 가입 트랜잭션에서 Keycloak 생성 직후 채움(실패 시 행 삭제) / 소셜: 첫 로그인 시 이 값으로 행 생성',
	`email`	VARCHAR(100)	NOT NULL	COMMENT '로그인 ID = Keycloak username. 관리자 검색 · 표시용 사본 (소셜은 Keycloak First Broker Login에서 필수 입력)',
	`name`	VARCHAR(50)	NOT NULL	COMMENT '이름',
	`nick`	VARCHAR(30)	NULL	COMMENT '닉네임 (선택)',
	`phone`	VARCHAR(20)	NULL	COMMENT '휴대폰 번호 예: 010-1234-5678 — 소셜 가입은 비어 있을 수 있음, 주문 전 입력 받음',
	`signup_provider`	VARCHAR(20)	NOT NULL	DEFAULT 'LOCAL'	COMMENT 'LOCAL / KAKAO / GOOGLE / NAVER — 가입 경로 (표시 · 통계용, 인증 판단에는 쓰지 않음). 소셜 계정 연결은 Keycloak이 관리',
	`point_balance`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '포인트 잔액 (원). 차감은 조건부 UPDATE (point_balance >= :p) — 음수 금지',
	`total_confirmed_amt`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '누적 구매확정 금액 (원) — OrderItemConfirmed 수신 시 가산, 등급 산정 기준',
	`grade`	VARCHAR(20)	NOT NULL	DEFAULT 'NORMAL'	COMMENT 'NORMAL / SILVER / GOLD — 혜택은 표시만 (적립률 5% 고정)',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'ACTIVE'	COMMENT 'ACTIVE / SUSPENDED — 정지 시 Keycloak 계정 enabled=false 동기화',
	`status_reason`	VARCHAR(255)	NULL	COMMENT '이용정지 · 해제 사유 (관리자 입력 필수)',
	`suspended_at`	DATETIME(6)	NULL	COMMENT '이용정지 시각 (애플리케이션이 기록)',
	`suspended_until`	DATETIME(6)	NULL	COMMENT '이용정지 종료 시각 — NULL 이면 영구 정지(관리자가 해제). 지나면 스케줄러가 자동 해제',
	`last_login_at`	DATETIME(6)	NULL	COMMENT '마지막 로그인 시각 (애플리케이션이 기록) — 관리자 회원 상세',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '가입 시각 (= joinedAt)',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`deleted_at`	DATETIME(6)	NULL	COMMENT '소프트 삭제 시각 (회원 물리 DELETE 금지)'
);

-- 회원 배송지
CREATE TABLE `address` (
	`address_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`label`	VARCHAR(30)	NOT NULL	COMMENT '배송지 이름 예: 집, 회사',
	`receiver_name`	VARCHAR(50)	NOT NULL	COMMENT '수령인',
	`receiver_phone`	VARCHAR(20)	NOT NULL	COMMENT '수령인 연락처',
	`zipcode`	VARCHAR(10)	NOT NULL	COMMENT '우편번호',
	`address1`	VARCHAR(200)	NOT NULL	COMMENT '기본 주소',
	`address2`	VARCHAR(200)	NULL	COMMENT '상세 주소',
	`is_default`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '기본 배송지 여부 — 회원당 1개는 서비스에서 보장 (지정 시 기존 기본 해제를 같은 트랜잭션에서)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`deleted_at`	DATETIME(6)	NULL	COMMENT '소프트 삭제 시각'
);

-- 포인트 적립 · 사용 · 환원 · 회수 이력
CREATE TABLE `point_history` (
	`point_history_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL,
	`order_item_id`	BIGINT	NULL	COMMENT 'order-service order_item.order_item_id (논리 참조) — 상품주문별 배분 · 환원 · 회수 단위. USE는 NULL',
	`order_no`	VARCHAR(20)	NULL	COMMENT 'order-service orders.order_no (논리 참조) — 이력 화면 표시용',
	`type`	VARCHAR(20)	NOT NULL	COMMENT 'EARN(적립) / USE(사용) / RESTORE(사용 환원) / RECLAIM(적립 회수)',
	`amount`	BIGINT	NOT NULL	COMMENT '변동 포인트 (원, 항상 양수) — 증감 방향은 type으로 판단',
	`shortfall_amt`	BIGINT	NOT NULL	DEFAULT 0	COMMENT 'RECLAIM에서 잔액 부족으로 회수하지 못한 차액 (원) — 잔액은 0까지만 회수',
	`balance_after`	BIGINT	NOT NULL	COMMENT '변동 후 잔액 (원)',
	`ref_key`	VARCHAR(64)	NOT NULL	COMMENT '멱등 키 — USE · 보상 RESTORE: sagaId / 클레임 RESTORE · RECLAIM: claim-{claimId}-{orderItemId} / EARN: {eventId}-{orderItemId}',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 소비한 이벤트 ID (중복 처리 방지)
CREATE TABLE `userdb_processed_event` (
	`processed_event_id`	BIGINT	NOT NULL,
	`event_id`	VARCHAR(36)	NOT NULL	COMMENT '이벤트 봉투 eventId (UUID)',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT 'StoreApproved / OrderPaid / OrderItemConfirmed',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '처리 시각'
);

-- 포인트 사용 sagaId 표식 (늦게 도착한 사용 차단)
CREATE TABLE `point_use_saga` (
	`point_use_saga_id`	BIGINT	NOT NULL,
	`saga_id`	VARCHAR(64)	NOT NULL	COMMENT 'order 주문 Saga ID — point_history.ref_key(USE · 보상 RESTORE)와 같은 값',
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'USED(사용 처리됨) / RELEASED(사용 전에 취소됨 — 이후 사용 거절)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- ============================================================ storedb (store-service · 담당 박지연)
-- 원본: V1__init_store_ddl.sql · V2__store_display_columns.sql · V3__store_comment_cleanup.sql · V4__event_outbox.sql

-- 입점 신청서 (진행 중 신청 1건은 서비스 검증)
CREATE TABLE `store_application` (
	`store_application_id`	BIGINT	NOT NULL,
	`user_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조) — 신청자',
	`category_id`	BIGINT	NOT NULL	COMMENT 'product-service category.category_id (논리 참조) — 희망 카테고리',
	`biz_type`	VARCHAR(20)	NOT NULL	COMMENT 'INDIVIDUAL / CORPORATE — 서류는 같고 심사 기준만 다름',
	`company_name`	VARCHAR(100)	NOT NULL	COMMENT '상호',
	`owner_name`	VARCHAR(50)	NOT NULL	COMMENT '대표자 — 스토어 홈 판매자 정보로 노출',
	`biz_reg_no`	VARCHAR(12)	NOT NULL	COMMENT '사업자등록번호 000-00-00000 — 스토어 홈 판매자 정보로 노출. 바뀌면 새 사업자(재심사)',
	`ecommerce_reg_no`	VARCHAR(50)	NOT NULL	COMMENT '통신판매업 신고번호',
	`manager_phone`	VARCHAR(20)	NOT NULL	COMMENT '담당자 휴대폰 — 승인 시 store.cs_phone 으로 복사',
	`manager_email`	VARCHAR(100)	NOT NULL	COMMENT '심사 결과(승인 · 반려 · 보완요청) 메일 수신 주소 — 이벤트 payload 에 포함',
	`business_address`	VARCHAR(255)	NOT NULL	COMMENT '사업장 주소',
	`store_name`	VARCHAR(50)	NOT NULL	COMMENT '신청 당시 스토어명 — 승인 시 store.store_name 으로 복사',
	`slug`	VARCHAR(50)	NOT NULL	COMMENT '신청한 스토어 주소 gotgan.kr/store/{slug} — 승인 시 store.slug 로 복사. 형식(영소문자 · 숫자 · -)과 중복은 서비스 검증',
	`intro`	TEXT	NULL	COMMENT '스토어 소개 (sanitizer 정화 HTML) — 승인 시 store.intro 초기값',
	`representative_products`	JSON	NOT NULL	COMMENT '대표 상품 2~10개 [{name, subCategory, price}] — 심사 화면 표시용 (검색 · 집계 없음)',
	`expected_monthly_sales`	VARCHAR(20)	NULL	COMMENT '월 예상 매출 UNDER_5M / FROM_5M_TO_20M / FROM_20M_TO_100M / OVER_100M — 심사 화면 표시용',
	`sales_channel`	VARCHAR(255)	NULL	COMMENT '운영 중인 채널 (자사몰 · 인스타그램 등 URL) — 선택 입력, 심사 참고용',
	`bank_name`	VARCHAR(30)	NOT NULL	COMMENT '정산 은행',
	`account_no`	VARCHAR(30)	NOT NULL	COMMENT '정산 계좌번호',
	`account_holder`	VARCHAR(50)	NOT NULL	COMMENT '예금주',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'PENDING'	COMMENT 'PENDING / SUPPLEMENT / APPROVED / REJECTED',
	`status_reason`	VARCHAR(255)	NULL	COMMENT '가장 최근 반려 · 보완 사유 (전체는 application_history)',
	`marketing_agreed`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '마케팅 수신 동의 (선택 약관)',
	`terms_agreed_at`	DATETIME(6)	NOT NULL	COMMENT '필수 약관 동의 시각 (애플리케이션이 기록)',
	`status_changed_at`	DATETIME(6)	NOT NULL	COMMENT '마지막 상태 변경 시각 — 7일 자동 반려(SUPPLEMENT) · 30일 재신청 제한(REJECTED) 기준',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '제출 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`version`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '낙관적 락(@Version) — 재제출과 자동 반려가 동시에 저장되는 것 방지'
);

-- 입점 신청 서류
CREATE TABLE `application_document` (
	`application_document_id`	BIGINT	NOT NULL,
	`store_application_id`	BIGINT	NOT NULL,
	`doc_type`	VARCHAR(30)	NOT NULL	COMMENT 'BUSINESS_REG / ECOMMERCE_REG / BANK_COPY / PRODUCT_PHOTO / CATEGORY_CERT',
	`s3_key`	VARCHAR(255)	NOT NULL	COMMENT 'S3 seller-docs 객체 키 (비공개 — 볼 때마다 presigned GET 발급)',
	`original_name`	VARCHAR(255)	NOT NULL	COMMENT '원본 파일명 (관리자 화면 표시)',
	`content_type`	VARCHAR(50)	NOT NULL	COMMENT 'application/pdf · image/jpeg · image/png',
	`file_size`	INT UNSIGNED	NOT NULL	COMMENT '파일 크기 (바이트, 10MB 이하)',
	`is_current`	BOOLEAN	NOT NULL	DEFAULT TRUE	COMMENT '현재 유효한 서류 여부 — 재제출되면 FALSE',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 입점 신청 상태 이력 (STR-08 타임라인)
CREATE TABLE `application_history` (
	`application_history_id`	BIGINT	NOT NULL,
	`store_application_id`	BIGINT	NOT NULL,
	`from_status`	VARCHAR(20)	NULL	COMMENT '변경 전 상태 — 최초 신청은 NULL',
	`to_status`	VARCHAR(20)	NOT NULL	COMMENT '변경 후 상태',
	`actor_type`	VARCHAR(10)	NOT NULL	COMMENT 'APPLICANT / ADMIN / SYSTEM(7일 자동 반려) — 관리자 ID는 남기지 않음',
	`reason`	VARCHAR(255)	NULL	COMMENT '반려 · 보완 사유',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '상태가 바뀐 시각'
);

-- 스토어 · 배송 정책
CREATE TABLE `store` (
	`store_id`	BIGINT	NOT NULL,
	`store_application_id`	BIGINT	NOT NULL	COMMENT '원래 입점 신청 — 대표자 · 사업자번호는 이 신청서를 조인해 노출',
	`user_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조) — 판매자',
	`store_name`	VARCHAR(50)	NOT NULL	COMMENT '스토어명',
	`slug`	VARCHAR(50)	NOT NULL	COMMENT '스토어 주소 gotgan.kr/store/{slug} — 승인 시 신청서에서 복사, 판매자는 수정 불가',
	`intro`	TEXT	NULL	COMMENT '스토어 소개 (sanitizer 정화 HTML)',
	`image_key`	VARCHAR(255)	NULL	COMMENT '대표 이미지 S3 images 키 (stores/ 공개 경로)',
	`color`	VARCHAR(7)	NOT NULL	DEFAULT '#5b6b4a'	COMMENT '스토어 표시 색 (#RRGGBB) — 이미지 없을 때 아바타 배경, 판매자 설정에서 선택',
	`cs_phone`	VARCHAR(20)	NOT NULL	COMMENT '고객 문의 연락처 — 승인 시 신청서 담당자 휴대폰 복사, 이후 판매자 수정',
	`shipping_fee`	BIGINT	NOT NULL	DEFAULT 3000	COMMENT '스토어 묶음 기본 배송비 (원) — 승인 직후 3,000원',
	`free_shipping_threshold`	BIGINT	NULL	COMMENT '무료배송 기준 금액 (원) — NULL 이면 무료배송 없음',
	`courier`	VARCHAR(30)	NULL	COMMENT '기본 택배사 이름 예: CJ대한통운 — 상품 상세 배송 안내 · 송장 입력 기본값 (order_item.courier 와 같은 형식). 승인 직후 NULL',
	`dispatch_note`	VARCHAR(100)	NULL	COMMENT '출고 안내 문구 예: 평일 14시 이전 결제 시 당일 출고',
	`grade`	VARCHAR(20)	NOT NULL	DEFAULT 'SPROUT'	COMMENT '판매자 등급 SPROUT(새싹) / POWER(파워) / BIG_POWER(빅파워) — 승인 시 SPROUT, 승급 기준은 미정',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'ACTIVE'	COMMENT 'ACTIVE / SUSPENDED(관리자 정지) / CLOSED(판매자 폐점 — 되돌릴 수 없음)',
	`status_reason`	VARCHAR(255)	NULL	COMMENT '가장 최근 정지 · 해제 사유 (전체는 store_status_history)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`deleted_at`	DATETIME(6)	NULL	COMMENT '소프트 삭제 시각 (물리 DELETE 금지)'
);

-- 스토어 상태 이력 (STR-14)
CREATE TABLE `store_status_history` (
	`store_status_history_id`	BIGINT	NOT NULL,
	`store_id`	BIGINT	NOT NULL,
	`from_status`	VARCHAR(20)	NULL	COMMENT '변경 전 상태 — 승인으로 생성될 때만 NULL',
	`to_status`	VARCHAR(20)	NOT NULL	COMMENT '변경 후 상태',
	`actor_type`	VARCHAR(10)	NOT NULL	COMMENT 'SELLER(폐점) / ADMIN(정지 · 해제)',
	`reason`	VARCHAR(255)	NULL	COMMENT '사유 — 관리자 정지 · 해제는 필수',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '상태가 바뀐 시각'
);

-- 이벤트 아웃박스 (Transactional Outbox)
CREATE TABLE `storedb_outbox_event` (
	`outbox_event_id`	BIGINT	NOT NULL,
	`event_id`	VARCHAR(36)	NOT NULL	COMMENT '봉투 eventId (UUID) — 소비자 processed_event 멱등 키, 같은 이벤트 두 번 저장 방지',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT 'StoreApproved / StoreApplicationRejected / StoreApplicationSupplementRequested / StoreStatusChanged',
	`aggregate_id`	VARCHAR(40)	NOT NULL	COMMENT '봉투 aggregateId — 스토어 ID 또는 입점 신청 ID',
	`payload`	JSON	NOT NULL	COMMENT '이벤트 payload (config docs/events.md)',
	`occurred_at`	DATETIME(6)	NOT NULL	COMMENT '봉투 occurredAt — 업무 시각(앱 Clock). 다시 보내도 같은 값',
	`status`	VARCHAR(10)	NOT NULL	DEFAULT 'NEW'	COMMENT 'NEW / SENT',
	`attempt_count`	INT	NOT NULL	DEFAULT 0	COMMENT '발행 시도 횟수 — 한도를 넘으면 ERROR 로그(수동 확인)',
	`last_error`	VARCHAR(200)	NULL	COMMENT '마지막 발행 실패 사유',
	`sent_at`	DATETIME(6)	NULL	COMMENT 'SNS 발행 시각',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- ============================================================ productdb (product-service · 담당 박지연)
-- 원본: V1__init_product_ddl.sql · V2__product_info_columns.sql · V3__store_status.sql

-- 상품 카테고리 (상위/하위 2단계)
CREATE TABLE `category` (
	`category_id`	BIGINT	NOT NULL,
	`parent_id`	BIGINT	NULL	COMMENT '상위 카테고리 — 상위는 NULL',
	`name`	VARCHAR(50)	NOT NULL	COMMENT '카테고리 이름',
	`name_en`	VARCHAR(50)	NULL	COMMENT '카테고리 영문명 예: Apparel — 선택 입력, 없으면 화면에서 표시하지 않음',
	`sort_order`	INT	NOT NULL	DEFAULT 0	COMMENT '같은 부모 안의 정렬 순서 (작을수록 먼저)',
	`is_active`	BOOLEAN	NOT NULL	DEFAULT TRUE	COMMENT '사용 여부 — 삭제 없음, FALSE 면 공개 트리에서 숨김 (store · settlement 가 category_id 를 논리 참조)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 상품
CREATE TABLE `product` (
	`product_id`	BIGINT	NOT NULL,
	`category_id`	BIGINT	NOT NULL	COMMENT '하위 카테고리만 (서비스 검증)',
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조)',
	`name`	VARCHAR(100)	NOT NULL	COMMENT '상품명',
	`price`	BIGINT	NOT NULL	COMMENT '판매가 (원, 판매자 입력)',
	`discount_rate`	DECIMAL(5,2)	NOT NULL	DEFAULT 0	COMMENT '즉시할인율 % (0~90, 판매자 입력) — 수정 화면 표시용, 비율이라 DECIMAL (컨벤션 3-6)',
	`sale_price`	BIGINT	NOT NULL	COMMENT '할인 적용가 (원) — 서버가 price · discount_rate 로 계산(10원 단위 반올림). 정렬 · 주문 금액 기준',
	`description`	TEXT	NULL	COMMENT '상세 설명 (sanitizer 정화 HTML)',
	`material`	VARCHAR(100)	NOT NULL	COMMENT '소재 · 원재료 (상품정보 제공고시) 예: 리넨 100%',
	`origin`	VARCHAR(50)	NOT NULL	COMMENT '제조국 · 원산지 (상품정보 제공고시) 예: 대한민국',
	`option1_name`	VARCHAR(30)	NULL	COMMENT '옵션 그룹 1 이름 예: 색상 — 옵션 없으면 NULL',
	`option2_name`	VARCHAR(30)	NULL	COMMENT '옵션 그룹 2 이름 예: 사이즈 — 최대 2개',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'ON_SALE'	COMMENT 'ON_SALE / STOPPED(판매자 판매중지) / BLOCKED(관리자 강제 판매중지 — 해제하면 STOPPED)',
	`status_reason`	VARCHAR(255)	NULL	COMMENT '강제 판매중지 사유',
	`is_store_active`	BOOLEAN	NOT NULL	DEFAULT TRUE	COMMENT '스토어 운영중 여부 — StoreStatusChanged 로 갱신, FALSE 면 목록 · 검색에서 제외',
	`store_status_changed_at`	DATETIME(6)	NULL	COMMENT '마지막으로 반영한 StoreStatusChanged 의 occurredAt — 이보다 오래된 이벤트는 무시 (SQS 순서 미보장)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '등록 시각 (신상품 정렬)',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`deleted_at`	DATETIME(6)	NULL	COMMENT '소프트 삭제 시각 (주문 스냅샷 보존, 물리 DELETE 금지)'
);

-- 상품 옵션 조합 · 재고 (조합 중복은 서비스 검증)
CREATE TABLE `product_option` (
	`product_option_id`	BIGINT	NOT NULL,
	`product_id`	BIGINT	NOT NULL,
	`option1_value`	VARCHAR(30)	NULL	COMMENT '옵션 값 1 예: 빨강 — 기본 조합은 NULL',
	`option2_value`	VARCHAR(30)	NULL	COMMENT '옵션 값 2 예: M',
	`extra_price`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '추가금 (원) — 최종가 = product.sale_price + extra_price',
	`stock`	INT UNSIGNED	NOT NULL	DEFAULT 0	COMMENT '현재 재고 — 예약은 조건부 UPDATE(stock >= :qty), 판매자 수정은 본 값 비교(stock = :seen)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`deleted_at`	DATETIME(6)	NULL	COMMENT '소프트 삭제 시각 — 옵션 구성 변경으로 사라진 조합 (주문 · 장바구니 · stock_history 가 참조)'
);

-- 상품 이미지
CREATE TABLE `product_image` (
	`product_image_id`	BIGINT	NOT NULL,
	`product_id`	BIGINT	NOT NULL,
	`s3_key`	VARCHAR(255)	NOT NULL	COMMENT 'S3 images 객체 키 (products/ 공개 경로, 조회 URL = IMAGES_BASE_URL/{key})',
	`sort_order`	INT	NOT NULL	DEFAULT 0	COMMENT '갤러리 순서 — 0 이 대표 이미지',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 재고 예약 · 해제 · 복원 원장
CREATE TABLE `stock_history` (
	`stock_history_id`	BIGINT	NOT NULL,
	`product_option_id`	BIGINT	NOT NULL,
	`type`	VARCHAR(20)	NOT NULL	COMMENT 'RESERVE(예약 차감) / RELEASE(예약 해제) / RESTORE(취소 · 반품 복원)',
	`ref_key`	VARCHAR(64)	NOT NULL	COMMENT '멱등 키 — RESERVE · RELEASE: order-service saga_instance.saga_id / RESTORE: claim-{claimId}',
	`quantity`	INT UNSIGNED	NOT NULL	COMMENT '변동 수량 (항상 양수) — 증감 방향은 type 으로 판단',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 소비한 이벤트 ID (중복 처리 방지)
CREATE TABLE `productdb_processed_event` (
	`processed_event_id`	BIGINT	NOT NULL,
	`event_id`	VARCHAR(36)	NOT NULL	COMMENT '이벤트 봉투 eventId (UUID)',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT 'StoreStatusChanged',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '처리 시각'
);

-- 스토어 운영 상태 스냅샷 — StoreStatusChanged 로 갱신, 상품 등록 때 is_store_active 초기값
CREATE TABLE `store_status` (
	`store_id`	BIGINT	NOT NULL	COMMENT '스토어 ID (storedb store.store_id, 논리 참조)',
	`status`	VARCHAR(20)	NOT NULL	COMMENT '마지막으로 반영한 StoreStatusChanged.status — ACTIVE 만 운영 중, 그 밖의 값(SUSPENDED · CLOSED …)은 상품 숨김',
	`status_changed_at`	DATETIME(6)	NOT NULL	COMMENT '마지막으로 반영한 이벤트 occurredAt — 이보다 오래된 이벤트는 무시 (SQS 순서 미보장)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- ============================================================ orderdb (order-service · 담당 이경석)
-- 원본: V1__init.sql · V2__order_saga_outbox.sql · V3__saga_retry.sql · V4__order_item_ordered.sql · V5__order_claim_restore_point.sql · V6__outbox_attempts.sql

-- 장바구니 항목 (원본, Redis 캐시의 출처)
CREATE TABLE `cart_item` (
	`cart_item_id`	BIGINT	NOT NULL	COMMENT '장바구니 항목 PK',
	`user_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조)',
	`product_id`	BIGINT	NOT NULL	COMMENT 'product-service product.product_id (논리 참조)',
	`product_option_id`	BIGINT	NOT NULL	COMMENT 'product-service product_option.product_option_id (논리 참조)',
	`quantity`	INT UNSIGNED	NOT NULL	COMMENT '담은 수량',
	`checked`	BOOLEAN	NOT NULL	DEFAULT TRUE	COMMENT '주문 대상 선택 여부',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각'
);

-- 주문 (결제 단위)
CREATE TABLE `orders` (
	`order_id`	BIGINT	NOT NULL	COMMENT '주문 PK',
	`buyer_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조)',
	`order_no`	VARCHAR(20)	NOT NULL	COMMENT '외부 노출 주문번호 예: 20261021-000123',
	`orderer_name`	VARCHAR(50)	NOT NULL	COMMENT '주문 시점 스냅샷 (실명 users.name) — 주문 확인 메일 등 알림용, 화면 공개 금지',
	`orderer_display_name`	VARCHAR(50)	NOT NULL	COMMENT '주문 시점 스냅샷 (공개 표시용 이름) — users.nick 이 있으면 nick, NULL · 공백이면 users.name 을 마스킹한 값(예: 홍*동). 주문 생성 시 애플리케이션이 결정. OrderItemConfirmed.buyerDisplayName 으로 review 에 전달',
	`orderer_email`	VARCHAR(100)	NOT NULL	COMMENT '주문 시점 스냅샷 — 알림 Lambda가 user-service를 부르지 않게',
	`receiver_name`	VARCHAR(50)	NOT NULL	COMMENT '수령인',
	`receiver_phone`	VARCHAR(20)	NOT NULL	COMMENT '수령인 연락처',
	`zipcode`	VARCHAR(10)	NOT NULL	COMMENT '우편번호',
	`address1`	VARCHAR(200)	NOT NULL	COMMENT '기본 주소',
	`address2`	VARCHAR(200)	NULL	COMMENT '상세 주소',
	`delivery_memo`	VARCHAR(200)	NULL	COMMENT '배송 요청사항',
	`total_product_amt`	BIGINT	NOT NULL	COMMENT '상품 금액 합계 (원)',
	`total_shipping_fee`	BIGINT	NOT NULL	COMMENT '배송비 합계 (원)',
	`used_point`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '사용 포인트 합계 (원) — user-service 차감은 Saga 2단계',
	`pay_amt`	BIGINT	NOT NULL	COMMENT '실결제금액 (원) = 상품 + 배송비 - 사용 포인트, 포트원 결제창 금액',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'PENDING'	COMMENT 'PENDING / PAID / FAILED / CANCELLED',
	`paid_at`	DATETIME(6)	NULL	COMMENT '결제 완료 시각 (애플리케이션이 기록)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각',
	`version`	BIGINT	NOT NULL	DEFAULT 0	COMMENT 'JPA @Version 낙관적 락'
);

-- 주문의 스토어별 배송비
CREATE TABLE `order_store_shipment` (
	`order_store_shipment_id`	BIGINT	NOT NULL	COMMENT '스토어별 배송비 PK',
	`order_id`	BIGINT	NOT NULL	COMMENT 'orders.order_id',
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조)',
	`shipping_fee`	BIGINT	NOT NULL	COMMENT '스토어 묶음 배송비 (원)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각'
);

-- 상품주문 (상태 관리 단위)
CREATE TABLE `order_item` (
	`order_item_id`	BIGINT	NOT NULL	COMMENT '상품주문 PK',
	`order_id`	BIGINT	NOT NULL	COMMENT 'orders.order_id',
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조) — 판매자 주문 조회 핵심 키',
	`product_id`	BIGINT	NOT NULL	COMMENT 'product-service product.product_id (논리 참조)',
	`product_option_id`	BIGINT	NOT NULL	COMMENT 'product-service product_option.product_option_id (논리 참조)',
	`category_id`	BIGINT	NOT NULL	COMMENT 'product-service category.category_id (논리 참조) — 하위 카테고리 스냅샷 (OrderItemConfirmed.categoryId)',
	`top_category_id`	BIGINT	NOT NULL	COMMENT 'product-service category.category_id (논리 참조) — 상위 카테고리 스냅샷, 판매수수료 기준 (OrderItemConfirmed.topCategoryId, events.md 10/16)',
	`item_no`	VARCHAR(24)	NOT NULL	COMMENT '외부 노출 상품주문번호 예: 20261021-000123-01',
	`store_name`	VARCHAR(100)	NOT NULL	COMMENT '주문 시점 스냅샷',
	`product_name`	VARCHAR(200)	NOT NULL	COMMENT '주문 시점 스냅샷 (OrderItemConfirmed.productName)',
	`option_name`	VARCHAR(200)	NULL	COMMENT '주문 시점 스냅샷 예: 오트밀 / M',
	`thumbnail_url`	VARCHAR(500)	NULL	COMMENT '주문 시점 스냅샷 — 주문 목록 썸네일',
	`unit_price`	BIGINT	NOT NULL	COMMENT '단가 (원)',
	`quantity`	INT UNSIGNED	NOT NULL	COMMENT '수량',
	`item_amt`	BIGINT	NOT NULL	COMMENT '상품주문 금액 (원)',
	`used_point`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '금액 비율로 배분한 사용 포인트 (원) — 취소 · 반품 시 환원액',
	`earned_point`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '적립 포인트 (원) = (item_amt - used_point)의 5% — 취소 · 반품 시 회수액',
	`courier`	VARCHAR(30)	NULL	COMMENT '택배사',
	`tracking_no`	VARCHAR(40)	NULL	COMMENT '송장번호',
	`status`	VARCHAR(30)	NOT NULL	DEFAULT 'ORDERED'	COMMENT 'ORDERED / PAYMENT_DONE / PREPARING / SHIPPING / DELIVERED / CONFIRMED / CANCEL_REQUESTED / CANCELLED / RETURN_REQUESTED / RETURNED / EXCHANGE_REQUESTED',
	`shipped_at`	DATETIME(6)	NULL	COMMENT '발송 시각',
	`delivered_at`	DATETIME(6)	NULL	COMMENT '배송 완료 시각',
	`confirmed_at`	DATETIME(6)	NULL	COMMENT '구매확정 시각 — 정산 기준일',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각',
	`version`	BIGINT	NOT NULL	DEFAULT 0	COMMENT 'JPA @Version 낙관적 락'
);

-- 상품주문 상태 변경 이력
CREATE TABLE `order_item_history` (
	`order_item_history_id`	BIGINT	NOT NULL	COMMENT '상태 이력 PK',
	`order_item_id`	BIGINT	NOT NULL	COMMENT 'order_item.order_item_id',
	`actor_id`	BIGINT	NULL	COMMENT 'user-service users.user_id (논리 참조), SYSTEM이면 NULL',
	`from_status`	VARCHAR(30)	NULL	COMMENT '변경 전 상태',
	`to_status`	VARCHAR(30)	NOT NULL	COMMENT '변경 후 상태',
	`actor_type`	VARCHAR(10)	NOT NULL	COMMENT 'BUYER / SELLER / ADMIN / SYSTEM',
	`reason`	VARCHAR(300)	NULL	COMMENT '사유',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각'
);

-- 취소 · 반품 · 교환 요청
CREATE TABLE `order_claim` (
	`order_claim_id`	BIGINT	NOT NULL	COMMENT '클레임 PK',
	`order_item_id`	BIGINT	NOT NULL	COMMENT 'order_item.order_item_id',
	`requester_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조) — 요청한 구매자',
	`claim_type`	VARCHAR(20)	NOT NULL	COMMENT 'CANCEL / RETURN / EXCHANGE',
	`reason_code`	VARCHAR(30)	NOT NULL	COMMENT 'CHANGE_OF_MIND / DEFECTIVE / WRONG_DELIVERY / DELAYED / ETC',
	`reason_detail`	VARCHAR(500)	NULL	COMMENT '상세 사유 (구매자 입력)',
	`reject_reason`	VARCHAR(500)	NULL	COMMENT '판매자 거부 사유',
	`refund_amt`	BIGINT	NULL	COMMENT '환불 금액 (원) — 교환이면 NULL (OrderItemCancelled · Returned.refundAmt)',
	`restore_point`	BIGINT	NULL	COMMENT '돌려줄 사용 포인트 (원) — 교환이면 NULL (OrderItemCancelled · Returned.restoredPoint)',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'REQUESTED'	COMMENT 'REQUESTED / APPROVED / REJECTED / COMPLETED',
	`requested_at`	DATETIME(6)	NOT NULL	COMMENT '요청 시각 (애플리케이션이 기록)',
	`processed_at`	DATETIME(6)	NULL	COMMENT '승인 · 거부 · 완료 처리 시각',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각'
);

-- Saga 진행 상태
CREATE TABLE `saga_instance` (
	`saga_instance_id`	BIGINT	NOT NULL	COMMENT 'Saga PK',
	`order_id`	BIGINT	NOT NULL	COMMENT 'orders.order_id',
	`order_claim_id`	BIGINT	NULL	COMMENT 'order_claim.order_claim_id — 취소 · 반품 Saga일 때만',
	`saga_id`	CHAR(36)	NOT NULL	COMMENT 'UUID — Saga 참여자 API(user · product · payment) 멱등 키',
	`saga_type`	VARCHAR(30)	NOT NULL	COMMENT 'PLACE_ORDER / CANCEL_ITEM / RETURN_ITEM',
	`current_step`	VARCHAR(30)	NOT NULL	COMMENT 'PLACE_ORDER: ORDER_CREATED / POINT_USED / STOCK_RESERVED / PAYMENT_READY / PAYMENT_VERIFIED / DONE, CANCEL_ITEM · RETURN_ITEM: PAYMENT_CANCEL_REQUESTED / PAYMENT_CANCELLED / STOCK_RESTORED / POINT_RESTORED / DONE',
	`attempt_count`	INT	NOT NULL	DEFAULT 0	COMMENT '현재 단계 재시도 횟수 — 단계가 넘어가면 0, 8회 넘으면 FAILED',
	`last_error`	VARCHAR(500)	NULL	COMMENT '마지막 실패 사유',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'STARTED'	COMMENT 'STARTED / COMPENSATING / COMPLETED / COMPENSATED / FAILED',
	`next_retry_at`	DATETIME(6)	NULL	COMMENT '다음 재시도 시각 — NULL 이면 재시도 대상 아님 (복구 스케줄러가 status · next_retry_at 으로 조회)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각'
);

-- Transactional Outbox
CREATE TABLE `orderdb_outbox_event` (
	`outbox_event_id`	BIGINT	NOT NULL	COMMENT 'Outbox PK',
	`event_id`	VARCHAR(36)	NOT NULL	COMMENT '이벤트 봉투 eventId (UUID) — 소비자 processed_event 멱등 키',
	`aggregate_type`	VARCHAR(30)	NOT NULL	COMMENT 'ORDER / ORDER_ITEM',
	`aggregate_id`	VARCHAR(40)	NOT NULL	COMMENT '주문번호 또는 상품주문번호',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT 'OrderPaid / OrderItemShipped / OrderItemConfirmed / OrderItemCancelled / OrderItemReturned',
	`payload`	JSON	NOT NULL	COMMENT '이벤트 payload (docs/events.md)',
	`status`	VARCHAR(10)	NOT NULL	DEFAULT 'NEW'	COMMENT 'NEW / SENT',
	`attempt_count`	INT	NOT NULL	DEFAULT 0	COMMENT '발행 시도 횟수 (경보 기준)',
	`last_error`	VARCHAR(200)	NULL	COMMENT '마지막 발행 실패 사유',
	`sent_at`	DATETIME(6)	NULL	COMMENT 'SNS 발행 시각',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각'
);

-- ============================================================ paymentdb (payment-service · 담당 구지훈)
-- 원본: V1__init.sql · V2__payment_cancel_write_ahead.sql

-- 결제 (포트원 V2)
CREATE TABLE `payment` (
	`payment_id`	BIGINT	NOT NULL,
	`order_id`	BIGINT	NOT NULL	COMMENT 'order-service orders.order_id (논리 참조)',
	`order_no`	VARCHAR(20)	NOT NULL	COMMENT 'order-service orders.order_no (논리 참조) — 준비 · 검증 · 조회 멱등 키',
	`portone_payment_id`	VARCHAR(64)	NOT NULL	COMMENT '포트원 paymentId = pay-{orderNo} — 우리가 만들어 결제창에 넘기는 값',
	`amount`	BIGINT	NOT NULL	COMMENT '기대 금액 = 실결제금액 (원, 1원 이상) — 포트원 조회 결과와 비교',
	`cancelled_amount`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '누적 취소 금액 (원) — 부분 취소 합계, amount 와 같아지면 CANCELLED',
	`pg_tx_id`	VARCHAR(100)	NULL	COMMENT '포트원 응답의 PG 거래 ID',
	`method`	VARCHAR(30)	NULL	COMMENT 'CARD / EASY_PAY ... (포트원 method.type 변환)',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'READY'	COMMENT 'READY / PAID / PARTIAL_CANCELLED / CANCELLED / FAILED',
	`paid_at`	DATETIME(6)	NULL	COMMENT '결제 완료 시각 (포트원 paidAt)',
	`cancelled_at`	DATETIME(6)	NULL	COMMENT '전액 취소 시각',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`version`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '낙관적 락(@Version) — 확인 요청과 웹훅이 동시에 와도 한 번만 반영'
);

-- 결제 취소 (전액 · 부분)
CREATE TABLE `payment_cancel` (
	`payment_cancel_id`	BIGINT	NOT NULL,
	`payment_id`	BIGINT	NOT NULL,
	`order_item_id`	BIGINT	NULL	COMMENT 'order-service order_item.order_item_id (논리 참조) — 전액 취소면 NULL',
	`saga_id`	CHAR(36)	NULL	COMMENT 'order-service saga_instance.saga_id (논리 참조) — 같은 취소 재요청 멱등 키',
	`cancel_amount`	BIGINT	NOT NULL	COMMENT '취소 금액 (원)',
	`cancellable_before`	BIGINT	NULL	COMMENT '취소 요청 직전 취소 가능 잔액 — 포트원 currentCancellableAmount 로 보내 같은 취소가 두 번 나가지 않게 함',
	`reason`	VARCHAR(200)	NOT NULL	COMMENT '취소 사유 예: 구매자 취소 / 반품 / 결제 금액 불일치 자동 취소 / 주문 처리 실패',
	`portone_cancellation_id`	VARCHAR(64)	NULL	COMMENT '포트원 취소 응답의 cancellation id',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'SUCCEEDED'	COMMENT 'PENDING(포트원 결과 미확정) / SUCCEEDED / FAILED — V1 행은 모두 성공 기록이라 기본값 SUCCEEDED',
	`attempt_count`	INT	NOT NULL	DEFAULT 0	COMMENT '포트원 취소 요청 · 대조 시도 횟수 (복구 스케줄러 경보 기준)',
	`last_error`	VARCHAR(200)	NULL	COMMENT '마지막 실패 · 미확정 사유 (포트원 오류 type 등, 비밀 값 금지)',
	`cancelled_at`	DATETIME(6)	NULL	COMMENT '포트원 취소 완료 시각 — SUCCEEDED 일 때만 값이 있음',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- ============================================================ reviewdb (review-service · 담당 이경석)
-- 원본: V1__init.sql · V2__init_reviewdb.sql · V3__drop_review_helpful_count.sql · V4__review_eligibility_option_thumbnail.sql · V5__drop_review_image_file_size.sql

-- 리뷰 작성 자격 (상품주문당 1개)
CREATE TABLE `review_eligibility` (
	`review_eligibility_id`	BIGINT	NOT NULL	COMMENT '작성 자격 PK',
	`order_item_id`	BIGINT	NOT NULL	COMMENT 'order-service order_item.order_item_id (논리 참조)',
	`user_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조) — 구매자 (payload buyerId)',
	`product_id`	BIGINT	NOT NULL	COMMENT 'product-service product.product_id (논리 참조)',
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조)',
	`buyer_display_name`	VARCHAR(50)	NOT NULL	COMMENT '구매자 공개 표시용 이름 스냅샷 (payload buyerDisplayName = order 의 orderer_display_name) — nick 또는 마스킹된 실명, 가공 없이 그대로 표시',
	`product_name`	VARCHAR(200)	NOT NULL	COMMENT '구매확정 시점 상품명 스냅샷 (payload productName) — 작성 가능 목록 표시용',
	`option_name`	VARCHAR(200)	NULL	COMMENT '구매확정 시점 옵션명 스냅샷 (payload optionName, 예: 오트밀 / M) — 옵션 없는 상품은 NULL',
	`thumbnail_url`	VARCHAR(500)	NULL	COMMENT '구매확정 시점 상품 대표 사진 URL 스냅샷 (payload thumbnailUrl)',
	`confirmed_at`	DATETIME(6)	NOT NULL	COMMENT '구매확정 시각 (payload confirmedAt)',
	`expires_at`	DATETIME(6)	NOT NULL	COMMENT '작성 기한 = confirmed_at + 30일',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각'
);

-- 상품 리뷰
CREATE TABLE `review` (
	`review_id`	BIGINT	NOT NULL	COMMENT '리뷰 PK',
	`review_eligibility_id`	BIGINT	NOT NULL	COMMENT 'review_eligibility.review_eligibility_id',
	`order_item_id`	BIGINT	NOT NULL	COMMENT 'order-service order_item.order_item_id (논리 참조) — 상품주문당 1개',
	`user_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조) — 작성자',
	`product_id`	BIGINT	NOT NULL	COMMENT 'product-service product.product_id (논리 참조)',
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조)',
	`writer_display_name`	VARCHAR(50)	NOT NULL	COMMENT '작성자 공개 표시용 이름 (작성 시 review_eligibility.buyer_display_name 복사)',
	`rating`	TINYINT UNSIGNED	NOT NULL	COMMENT '별점 1~5',
	`title`	VARCHAR(100)	NULL	COMMENT '한줄 요약 (목업 title)',
	`content`	TEXT	NOT NULL	COMMENT '본문 (OWASP sanitizer 정화 후 저장)',
	`fit`	VARCHAR(20)	NULL	COMMENT '사이즈 체감 SMALL / TRUE_TO_SIZE / LARGE — 패션 · 신발가방만 (목업 fit)',
	`helpful_count`	INT UNSIGNED	NOT NULL	DEFAULT 0	COMMENT '도움돼요 수 (목업 helpful)',
	`reply_content`	TEXT	NULL	COMMENT '판매자 답글 (1개, sanitizer 정화)',
	`is_blinded`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '관리자 블라인드 여부 — 구매자 목록 · 평균 · 리뷰 수에서 제외, 판매자 목록엔 "블라인드됨"(답글 불가)',
	`blind_reason`	VARCHAR(255)	NULL	COMMENT '블라인드 사유 (해제하면 NULL)',
	`replied_at`	DATETIME(6)	NULL	COMMENT '판매자 답글 작성 · 수정 시각',
	`blinded_at`	DATETIME(6)	NULL	COMMENT '블라인드 시각 (해제하면 NULL)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각',
	`deleted_at`	DATETIME(6)	NULL	COMMENT '삭제 시각 (soft delete) — 평균 · 분포 집계에서 제외'
);

-- 리뷰 사진
CREATE TABLE `review_image` (
	`review_image_id`	BIGINT	NOT NULL	COMMENT '리뷰 사진 PK',
	`review_id`	BIGINT	NOT NULL	COMMENT 'review.review_id',
	`image_key`	VARCHAR(300)	NOT NULL	COMMENT 'S3 images 버킷 키 (reviews/...)',
	`sort_order`	INT UNSIGNED	NOT NULL	COMMENT '표시 순서 0~2',
	`file_size`	INT UNSIGNED	NOT NULL	COMMENT '파일 크기 (byte, 10MB 이하)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각'
);

-- 상품문의 · 답변
CREATE TABLE `product_inquiry` (
	`product_inquiry_id`	BIGINT	NOT NULL	COMMENT '상품문의 PK',
	`user_id`	BIGINT	NOT NULL	COMMENT 'user-service users.user_id (논리 참조) — 작성자',
	`product_id`	BIGINT	NOT NULL	COMMENT 'product-service product.product_id (논리 참조)',
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조) — 판매자 문의 목록 키',
	`content`	TEXT	NOT NULL	COMMENT '문의 본문 (sanitizer 정화)',
	`answer_content`	TEXT	NULL	COMMENT '판매자 답변 (sanitizer 정화)',
	`is_secret`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '비밀글 — 작성자 · 판매자만 본문 확인',
	`answered_at`	DATETIME(6)	NULL	COMMENT '답변 작성 · 수정 시각 (NULL = 미답변)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '수정 시각',
	`deleted_at`	DATETIME(6)	NULL	COMMENT '삭제 시각 (soft delete)'
);

-- 소비한 이벤트 ID (중복 처리 방지)
CREATE TABLE `reviewdb_processed_event` (
	`processed_event_id`	BIGINT	NOT NULL	COMMENT '처리 이벤트 PK',
	`event_id`	VARCHAR(36)	NOT NULL	COMMENT '이벤트 봉투 eventId (UUID)',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT 'OrderItemConfirmed',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '처리 시각'
);

-- ============================================================ settlementdb (settlement-service · 담당 구지훈)
-- 원본: V1__init.sql · V2__settlement_store_processed_event.sql · V3__settlement_commission_payout.sql · V4__settlement_history_product_name.sql · V5__settlement_store_seller_email.sql · V6__settlement_carried_over_count.sql · V7__event_outbox.sql · V8__remove_dormant_comments.sql

-- 정산용 스토어 스냅샷 (StoreApproved · StoreStatusChanged)
CREATE TABLE `settlement_store` (
	`settlement_store_id`	BIGINT	NOT NULL,
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service 스토어 ID (다른 서비스 ID — FK 없음)',
	`store_name`	VARCHAR(100)	NOT NULL	COMMENT 'StoreApproved 스냅샷 — 스토어명',
	`seller_email`	VARCHAR(100)	NULL	COMMENT 'StoreApproved 스냅샷 — 판매자 이메일 (SettlementPaid 명세서 메일 수신자)',
	`bank_name`	VARCHAR(50)	NULL	COMMENT 'StoreApproved 스냅샷 — 정산 계좌 은행',
	`account_number`	VARCHAR(50)	NULL	COMMENT 'StoreApproved 스냅샷 — 정산 계좌번호',
	`account_holder`	VARCHAR(50)	NULL	COMMENT 'StoreApproved 스냅샷 — 예금주',
	`store_status`	VARCHAR(20)	NOT NULL	DEFAULT 'ACTIVE'	COMMENT 'ACTIVE / SUSPENDED / CLOSED — ACTIVE 가 아니면 지급 보류',
	`store_status_changed_at`	DATETIME(6)	NULL	COMMENT '마지막으로 반영한 StoreStatusChanged 의 occurredAt — 이보다 오래된 이벤트는 무시 (SQS 순서 미보장)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 소비한 이벤트 ID (중복 처리 방지)
CREATE TABLE `settlementdb_processed_event` (
	`processed_event_id`	BIGINT	NOT NULL,
	`event_id`	VARCHAR(36)	NOT NULL	COMMENT '이벤트 봉투 eventId (UUID)',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT 'StoreApproved · StoreStatusChanged · OrderItemConfirmed',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '처리 시각'
);

-- 카테고리별 판매수수료율 (STL-01)
CREATE TABLE `category_commission` (
	`category_commission_id`	BIGINT	NOT NULL,
	`category_id`	BIGINT	NOT NULL	COMMENT 'product-service category.category_id (논리 참조) — 상위 카테고리',
	`commission_rate`	DECIMAL(5,2)	NOT NULL	COMMENT '판매수수료율 (%) 예: 11.00 — 결제수수료 2.2% 는 별도',
	`effective_from`	DATE	NOT NULL	COMMENT '적용 시작일 (KST) — 이 날 0시 이후 구매확정분부터. 변경은 판매자 공지 후 7일 뒤',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 주간 정산서 (STL-03 · 05 · 07 · 09)
CREATE TABLE `settlement` (
	`settlement_id`	BIGINT	NOT NULL,
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조) — settlement_store 로 스토어명 · 계좌 확인',
	`week_start`	DATE	NOT NULL	COMMENT '정산 주차 시작일 (월요일, KST)',
	`week_end`	DATE	NOT NULL	COMMENT '정산 주차 종료일 (일요일, KST)',
	`item_count`	INT UNSIGNED	NOT NULL	COMMENT '정산 대상 상품주문 수',
	`carried_over_count`	INT UNSIGNED	NOT NULL	DEFAULT 0	COMMENT '이월 상품주문 수 — 구매확정일이 이 주차 밖(지난 주차가 이미 지급돼 넘어온 건). item_count 에 포함',
	`sales_amt`	BIGINT	NOT NULL	COMMENT '구매확정 매출 (원) = Σ item_amt',
	`commission_amt`	BIGINT	NOT NULL	COMMENT '판매수수료 (원) = Σ commission_amt',
	`payment_fee_amt`	BIGINT	NOT NULL	COMMENT '결제수수료 (원) = Σ payment_fee_amt',
	`payout_amt`	BIGINT	NOT NULL	COMMENT '지급액 (원) = 매출 − 판매수수료 − 결제수수료',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'SCHEDULED'	COMMENT 'SCHEDULED(지급예정) / HELD(지급보류) / PAID(지급완료)',
	`hold_reason`	VARCHAR(255)	NULL	COMMENT '보류 사유 — 스토어 정지 · 폐점(자동) 또는 관리자 입력',
	`scheduled_pay_date`	DATE	NOT NULL	COMMENT '지급 예정일 (KST)',
	`held_at`	DATETIME(6)	NULL	COMMENT '보류 시각',
	`paid_at`	DATETIME(6)	NULL	COMMENT '지급 완료 시각 — SettlementPaid 발행 기준',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '집계 시각',
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`version`	BIGINT	NOT NULL	DEFAULT 0	COMMENT '낙관적 락(@Version) — 지급 실행과 보류가 동시에 저장되는 것 방지'
);

-- 정산 대상 상품주문 (STL-02 · 04)
CREATE TABLE `settlement_item` (
	`settlement_item_id`	BIGINT	NOT NULL,
	`settlement_id`	BIGINT	NULL	COMMENT '주간 집계 때 연결 — 집계 전에는 NULL',
	`order_item_id`	BIGINT	NOT NULL	COMMENT 'order-service order_item.order_item_id (논리 참조)',
	`order_no`	VARCHAR(20)	NOT NULL	COMMENT 'order-service orders.order_no (논리 참조) — 판매자 정산 상세 표시용',
	`store_id`	BIGINT	NOT NULL	COMMENT 'store-service store.store_id (논리 참조)',
	`product_id`	BIGINT	NOT NULL	COMMENT 'product-service product.product_id (논리 참조)',
	`product_name`	VARCHAR(100)	NOT NULL	COMMENT '구매확정 시점 상품명 스냅샷 (OrderItemConfirmed productName) — 정산 상세 표시용',
	`category_id`	BIGINT	NOT NULL	COMMENT 'product-service category.category_id (논리 참조) — 상위 카테고리, 수수료율 조회 기준',
	`item_amt`	BIGINT	NOT NULL	COMMENT '상품주문 금액 (원) — 포인트 차감 전 = 판매자 매출',
	`commission_rate`	DECIMAL(5,2)	NOT NULL	COMMENT '적재 시점 판매수수료율 (%) 스냅샷',
	`commission_amt`	BIGINT	NOT NULL	COMMENT '판매수수료 (원) = item_amt × commission_rate, 원 단위 내림',
	`payment_fee_amt`	BIGINT	NOT NULL	COMMENT '결제수수료 (원) = item_amt × 2.2%, 원 단위 내림',
	`confirmed_at`	DATETIME(6)	NOT NULL	COMMENT '구매확정 시각 (KST) — 정산 주차 판정 기준',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '적재 시각'
);

-- 지급 실행 기록 (STL-06)
CREATE TABLE `payout` (
	`payout_id`	BIGINT	NOT NULL,
	`settlement_id`	BIGINT	NOT NULL,
	`payout_amt`	BIGINT	NOT NULL	COMMENT '지급액 (원) — settlement.payout_amt 와 같음',
	`bank_name`	VARCHAR(50)	NOT NULL	COMMENT '지급 은행 (settlement_store 스냅샷)',
	`account_number`	VARCHAR(50)	NOT NULL	COMMENT '지급 계좌번호 (settlement_store 스냅샷)',
	`account_holder`	VARCHAR(50)	NOT NULL	COMMENT '예금주 (settlement_store 스냅샷)',
	`paid_at`	DATETIME(6)	NOT NULL	COMMENT '지급 실행 시각 (애플리케이션이 기록)',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- 정산서 상태 이력 (STL-05 상세 · STL-09)
CREATE TABLE `settlement_history` (
	`settlement_history_id`	BIGINT	NOT NULL,
	`settlement_id`	BIGINT	NOT NULL,
	`from_status`	VARCHAR(20)	NULL	COMMENT '변경 전 상태 — 집계로 생성될 때만 NULL',
	`to_status`	VARCHAR(20)	NOT NULL	COMMENT '변경 후 상태 (SCHEDULED / HELD / PAID)',
	`actor_type`	VARCHAR(10)	NOT NULL	COMMENT 'SYSTEM(주간 집계 · 스토어 정지/폐점 자동 보류) / ADMIN — 관리자 ID는 남기지 않음 (storedb 이력과 같은 규칙)',
	`reason`	VARCHAR(255)	NULL	COMMENT '보류 · 해제 사유',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)	COMMENT '상태가 바뀐 시각'
);

-- 이벤트 아웃박스 (트랜잭션과 함께 남기고 커밋 후 발행)
CREATE TABLE `event_outbox` (
	`event_outbox_id`	BIGINT	NOT NULL,
	`event_id`	CHAR(36)	NOT NULL	COMMENT '이벤트 봉투 eventId (UUID) — 다시 보내도 같은 값이라 소비자가 중복을 거름',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT 'SettlementPaid',
	`aggregate_id`	VARCHAR(64)	NOT NULL	COMMENT '봉투 aggregateId (정산서 ID 등)',
	`payload`	TEXT	NOT NULL	COMMENT '보낼 봉투 JSON 그대로 (docs/events.md 형식)',
	`status`	VARCHAR(20)	NOT NULL	DEFAULT 'PENDING'	COMMENT 'PENDING(보내기 전 · 실패) / SENT',
	`attempt_count`	INT	NOT NULL	DEFAULT 0	COMMENT '발행 시도 횟수 (경보 기준)',
	`last_error`	VARCHAR(200)	NULL	COMMENT '마지막 발행 실패 사유',
	`sent_at`	DATETIME(6)	NULL	COMMENT 'SNS 발행 성공 시각',
	`created_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6),
	`updated_at`	DATETIME(6)	NOT NULL	DEFAULT CURRENT_TIMESTAMP(6)
);

-- ============================================================ PK
ALTER TABLE `users` ADD CONSTRAINT `PK_USERS` PRIMARY KEY (`user_id`);
ALTER TABLE `address` ADD CONSTRAINT `PK_ADDRESS` PRIMARY KEY (`address_id`);
ALTER TABLE `point_history` ADD CONSTRAINT `PK_POINT_HISTORY` PRIMARY KEY (`point_history_id`);
ALTER TABLE `userdb_processed_event` ADD CONSTRAINT `PK_USERDB_PROCESSED_EVENT` PRIMARY KEY (`processed_event_id`);
ALTER TABLE `point_use_saga` ADD CONSTRAINT `PK_POINT_USE_SAGA` PRIMARY KEY (`point_use_saga_id`);
ALTER TABLE `store_application` ADD CONSTRAINT `PK_STORE_APPLICATION` PRIMARY KEY (`store_application_id`);
ALTER TABLE `application_document` ADD CONSTRAINT `PK_APPLICATION_DOCUMENT` PRIMARY KEY (`application_document_id`);
ALTER TABLE `application_history` ADD CONSTRAINT `PK_APPLICATION_HISTORY` PRIMARY KEY (`application_history_id`);
ALTER TABLE `store` ADD CONSTRAINT `PK_STORE` PRIMARY KEY (`store_id`);
ALTER TABLE `store_status_history` ADD CONSTRAINT `PK_STORE_STATUS_HISTORY` PRIMARY KEY (`store_status_history_id`);
ALTER TABLE `storedb_outbox_event` ADD CONSTRAINT `PK_STOREDB_OUTBOX_EVENT` PRIMARY KEY (`outbox_event_id`);
ALTER TABLE `category` ADD CONSTRAINT `PK_CATEGORY` PRIMARY KEY (`category_id`);
ALTER TABLE `product` ADD CONSTRAINT `PK_PRODUCT` PRIMARY KEY (`product_id`);
ALTER TABLE `product_option` ADD CONSTRAINT `PK_PRODUCT_OPTION` PRIMARY KEY (`product_option_id`);
ALTER TABLE `product_image` ADD CONSTRAINT `PK_PRODUCT_IMAGE` PRIMARY KEY (`product_image_id`);
ALTER TABLE `stock_history` ADD CONSTRAINT `PK_STOCK_HISTORY` PRIMARY KEY (`stock_history_id`);
ALTER TABLE `productdb_processed_event` ADD CONSTRAINT `PK_PRODUCTDB_PROCESSED_EVENT` PRIMARY KEY (`processed_event_id`);
ALTER TABLE `store_status` ADD CONSTRAINT `PK_STORE_STATUS` PRIMARY KEY (`store_id`);
ALTER TABLE `cart_item` ADD CONSTRAINT `PK_CART_ITEM` PRIMARY KEY (`cart_item_id`);
ALTER TABLE `orders` ADD CONSTRAINT `PK_ORDERS` PRIMARY KEY (`order_id`);
ALTER TABLE `order_store_shipment` ADD CONSTRAINT `PK_ORDER_STORE_SHIPMENT` PRIMARY KEY (`order_store_shipment_id`);
ALTER TABLE `order_item` ADD CONSTRAINT `PK_ORDER_ITEM` PRIMARY KEY (`order_item_id`);
ALTER TABLE `order_item_history` ADD CONSTRAINT `PK_ORDER_ITEM_HISTORY` PRIMARY KEY (`order_item_history_id`);
ALTER TABLE `order_claim` ADD CONSTRAINT `PK_ORDER_CLAIM` PRIMARY KEY (`order_claim_id`);
ALTER TABLE `saga_instance` ADD CONSTRAINT `PK_SAGA_INSTANCE` PRIMARY KEY (`saga_instance_id`);
ALTER TABLE `orderdb_outbox_event` ADD CONSTRAINT `PK_ORDERDB_OUTBOX_EVENT` PRIMARY KEY (`outbox_event_id`);
ALTER TABLE `payment` ADD CONSTRAINT `PK_PAYMENT` PRIMARY KEY (`payment_id`);
ALTER TABLE `payment_cancel` ADD CONSTRAINT `PK_PAYMENT_CANCEL` PRIMARY KEY (`payment_cancel_id`);
ALTER TABLE `review_eligibility` ADD CONSTRAINT `PK_REVIEW_ELIGIBILITY` PRIMARY KEY (`review_eligibility_id`);
ALTER TABLE `review` ADD CONSTRAINT `PK_REVIEW` PRIMARY KEY (`review_id`);
ALTER TABLE `review_image` ADD CONSTRAINT `PK_REVIEW_IMAGE` PRIMARY KEY (`review_image_id`);
ALTER TABLE `product_inquiry` ADD CONSTRAINT `PK_PRODUCT_INQUIRY` PRIMARY KEY (`product_inquiry_id`);
ALTER TABLE `reviewdb_processed_event` ADD CONSTRAINT `PK_REVIEWDB_PROCESSED_EVENT` PRIMARY KEY (`processed_event_id`);
ALTER TABLE `settlement_store` ADD CONSTRAINT `PK_SETTLEMENT_STORE` PRIMARY KEY (`settlement_store_id`);
ALTER TABLE `settlementdb_processed_event` ADD CONSTRAINT `PK_SETTLEMENTDB_PROCESSED_EVENT` PRIMARY KEY (`processed_event_id`);
ALTER TABLE `category_commission` ADD CONSTRAINT `PK_CATEGORY_COMMISSION` PRIMARY KEY (`category_commission_id`);
ALTER TABLE `settlement` ADD CONSTRAINT `PK_SETTLEMENT` PRIMARY KEY (`settlement_id`);
ALTER TABLE `settlement_item` ADD CONSTRAINT `PK_SETTLEMENT_ITEM` PRIMARY KEY (`settlement_item_id`);
ALTER TABLE `payout` ADD CONSTRAINT `PK_PAYOUT` PRIMARY KEY (`payout_id`);
ALTER TABLE `settlement_history` ADD CONSTRAINT `PK_SETTLEMENT_HISTORY` PRIMARY KEY (`settlement_history_id`);
ALTER TABLE `event_outbox` ADD CONSTRAINT `PK_EVENT_OUTBOX` PRIMARY KEY (`event_outbox_id`);

-- ============================================================ FK (같은 DB 안의 관계만)
ALTER TABLE `address` ADD CONSTRAINT `fk_address_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
ALTER TABLE `point_history` ADD CONSTRAINT `fk_point_history_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
ALTER TABLE `application_document` ADD CONSTRAINT `fk_application_document_store_application` FOREIGN KEY (`store_application_id`) REFERENCES `store_application` (`store_application_id`);
ALTER TABLE `application_history` ADD CONSTRAINT `fk_application_history_store_application` FOREIGN KEY (`store_application_id`) REFERENCES `store_application` (`store_application_id`);
ALTER TABLE `store` ADD CONSTRAINT `fk_store_store_application` FOREIGN KEY (`store_application_id`) REFERENCES `store_application` (`store_application_id`);
ALTER TABLE `store_status_history` ADD CONSTRAINT `fk_store_status_history_store` FOREIGN KEY (`store_id`) REFERENCES `store` (`store_id`);
ALTER TABLE `category` ADD CONSTRAINT `fk_category_parent` FOREIGN KEY (`parent_id`) REFERENCES `category` (`category_id`);
ALTER TABLE `product` ADD CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`category_id`);
ALTER TABLE `product_option` ADD CONSTRAINT `fk_product_option_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`);
ALTER TABLE `product_image` ADD CONSTRAINT `fk_product_image_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`product_id`);
ALTER TABLE `stock_history` ADD CONSTRAINT `fk_stock_history_product_option` FOREIGN KEY (`product_option_id`) REFERENCES `product_option` (`product_option_id`);
ALTER TABLE `order_store_shipment` ADD CONSTRAINT `fk_order_store_shipment_orders` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`);
ALTER TABLE `order_item` ADD CONSTRAINT `fk_order_item_orders` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`);
ALTER TABLE `order_item_history` ADD CONSTRAINT `fk_order_item_history_order_item` FOREIGN KEY (`order_item_id`) REFERENCES `order_item` (`order_item_id`);
ALTER TABLE `order_claim` ADD CONSTRAINT `fk_order_claim_order_item` FOREIGN KEY (`order_item_id`) REFERENCES `order_item` (`order_item_id`);
ALTER TABLE `saga_instance` ADD CONSTRAINT `fk_saga_instance_orders` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`);
ALTER TABLE `saga_instance` ADD CONSTRAINT `fk_saga_instance_order_claim` FOREIGN KEY (`order_claim_id`) REFERENCES `order_claim` (`order_claim_id`);
ALTER TABLE `payment_cancel` ADD CONSTRAINT `fk_payment_cancel_payment` FOREIGN KEY (`payment_id`) REFERENCES `payment` (`payment_id`);
ALTER TABLE `review` ADD CONSTRAINT `fk_review_review_eligibility` FOREIGN KEY (`review_eligibility_id`) REFERENCES `review_eligibility` (`review_eligibility_id`);
ALTER TABLE `review_image` ADD CONSTRAINT `fk_review_image_review` FOREIGN KEY (`review_id`) REFERENCES `review` (`review_id`);
ALTER TABLE `settlement_item` ADD CONSTRAINT `fk_settlement_item_settlement` FOREIGN KEY (`settlement_id`) REFERENCES `settlement` (`settlement_id`);
ALTER TABLE `payout` ADD CONSTRAINT `fk_payout_settlement` FOREIGN KEY (`settlement_id`) REFERENCES `settlement` (`settlement_id`);
ALTER TABLE `settlement_history` ADD CONSTRAINT `fk_settlement_history_settlement` FOREIGN KEY (`settlement_id`) REFERENCES `settlement` (`settlement_id`);
