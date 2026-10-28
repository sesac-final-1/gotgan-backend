package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 입점 심사 상세 — 신청서 전체 + 서류 열람 주소 + 상태 이력 + 같은 사업자번호의 다른 신청 · 스토어 (docs/api/store.md 4절).
 * 필드는 추가만 한다. 조립은 ApplicationQueryService 가 한다
 */
public record InternalApplicationDetailResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "접수번호 — 표시 전용", example = "AP-2610-027")
        String applicationNo,

        @Schema(description = "신청 상태", example = "SUPPLEMENT")
        ApplicationStatus status,

        @Schema(description = "반려 · 보완요청 사유 (없으면 null)", example = "통신판매업 신고증 사본이 흐려 확인이 어렵습니다")
        String statusReason,

        @Schema(description = "제출 시각", example = "2026-10-22T14:05:11+09:00")
        OffsetDateTime submittedAt,

        @Schema(description = "마지막 상태 변경 시각", example = "2026-10-23T10:00:00+09:00")
        OffsetDateTime statusChangedAt,

        @Schema(description = "승인으로 만들어진 스토어 ID, 아니면 null", example = "1")
        Long storeId,

        @Schema(description = "사업자 유형", example = "INDIVIDUAL")
        BizType bizType,

        @Schema(description = "상호", example = "곳간상회")
        String companyName,

        @Schema(description = "대표자명", example = "홍길동")
        String ownerName,

        @Schema(description = "사업자등록번호", example = "000-00-00001")
        String bizRegNo,

        @Schema(description = "통신판매업 신고번호", example = "2026-서울성동-0000")
        String ecommerceRegNo,

        @Schema(description = "담당자 연락처", example = "010-0000-1234")
        String managerPhone,

        @Schema(description = "담당자 이메일", example = "seller@example.com")
        String managerEmail,

        @Schema(description = "사업장 주소", example = "서울시 성동구 성수이로 00")
        String businessAddress,

        @Schema(description = "스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "스토어 주소", example = "gotgan-store")
        String slug,

        @Schema(description = "주 카테고리 ID (상위) — 이름은 호출자가 붙임", example = "1")
        Long categoryId,

        @Schema(description = "스토어 소개 HTML (없으면 null)", example = "<p>리넨 의류를 만듭니다.</p>")
        String intro,

        @Schema(description = "판매 예정 대표 상품 2~10개")
        List<RepresentativeProduct> representativeProducts,

        @Schema(description = "예상 월 매출 구간 (선택 입력이라 null 가능)", example = "FROM_5M_TO_20M")
        ExpectedMonthlySales expectedMonthlySales,

        @Schema(description = "기존 판매 채널 (없으면 null)", example = "https://instagram.com/gotgan.store")
        String salesChannel,

        @Schema(description = "정산 계좌")
        SettlementAccount settlementAccount,

        @Schema(description = "마케팅 수신 동의", example = "false")
        boolean marketingAgreed,

        @Schema(description = "필수 약관 동의 시각", example = "2026-10-22T14:05:11+09:00")
        OffsetDateTime termsAgreedAt,

        @Schema(description = "서류 — 지금 서류와 재제출로 바뀐 이전 서류 모두, 종류별 · 올린 순서")
        List<Document> documents,

        @Schema(description = "상태 이력 — 오래된 순")
        List<History> history,

        @Schema(description = "같은 사업자등록번호의 이 신청을 뺀 다른 신청 · 스토어 — 중복 입점 확인 근거")
        SameBizRegNo sameBizRegNo) {

    public record SettlementAccount(
            @Schema(description = "은행", example = "국민")
            String bank,

            @Schema(description = "계좌번호", example = "000000-00-000000")
            String number,

            @Schema(description = "예금주", example = "홍길동")
            String holder) {
    }

    public record Document(
            @Schema(description = "서류 ID", example = "101")
            Long documentId,

            @Schema(description = "서류 종류", example = "ECOMMERCE_REG")
            DocType docType,

            @Schema(description = "올린 파일 이름", example = "통신판매업신고증.pdf")
            String originalName,

            @Schema(description = "S3 에서 확인한 형식", example = "application/pdf")
            String contentType,

            @Schema(description = "바이트", example = "482113")
            int fileSize,

            @Schema(description = "지금 서류면 true, 재제출로 바뀐 이전 서류면 false", example = "true")
            boolean current,

            @Schema(description = "올린 시각", example = "2026-10-22T14:05:11+09:00")
            OffsetDateTime uploadedAt,

            @Schema(description = "presigned GET 열람 주소 (10분) — 발급 실패면 null")
            String url,

            @Schema(description = "열람 주소 만료 시각 — 발급 실패면 null", example = "2026-10-26T15:10:00+09:00")
            OffsetDateTime urlExpiresAt) {
    }

    public record History(
            @Schema(description = "이전 상태 — 최초 신청은 null", example = "PENDING")
            ApplicationStatus fromStatus,

            @Schema(description = "바뀐 상태", example = "SUPPLEMENT")
            ApplicationStatus toStatus,

            @Schema(description = "바꾼 쪽 — 관리자 개인은 남기지 않음", example = "ADMIN")
            ActorType actor,

            @Schema(description = "사유 (없으면 null)", example = "통신판매업 신고증 사본이 흐려 확인이 어렵습니다")
            String reason,

            @Schema(description = "바뀐 시각", example = "2026-10-23T10:00:00+09:00")
            OffsetDateTime changedAt) {
    }

    public record SameBizRegNo(
            @Schema(description = "다른 신청 — 제출 최신순")
            List<SameApplication> applications,

            @Schema(description = "다른 신청으로 만든 스토어 — 폐점 포함, 소프트 삭제 제외")
            List<SameStore> stores) {
    }

    public record SameApplication(
            @Schema(description = "신청 ID", example = "9")
            Long applicationId,

            @Schema(description = "접수번호", example = "AP-2609-009")
            String applicationNo,

            @Schema(description = "스토어명", example = "곳간상점")
            String storeName,

            @Schema(description = "신청 상태", example = "REJECTED")
            ApplicationStatus status,

            @Schema(description = "제출 시각", example = "2026-09-14T11:00:00+09:00")
            OffsetDateTime submittedAt) {
    }

    public record SameStore(
            @Schema(description = "스토어 ID", example = "1")
            Long storeId,

            @Schema(description = "스토어명", example = "곳간상회")
            String storeName,

            @Schema(description = "스토어 상태", example = "CLOSED")
            StoreStatus status) {
    }
}
