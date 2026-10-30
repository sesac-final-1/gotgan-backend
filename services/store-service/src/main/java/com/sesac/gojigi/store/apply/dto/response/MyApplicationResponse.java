package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.History;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SettlementAccount;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 내 입점 신청 상태 — 신청자 화면의 접수 타임라인 · 보완요청 · 반려 안내 (docs/api/store.md 2-2절, STR-08). 필드는 추가만 한다 */
public record MyApplicationResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "접수번호 — 표시 전용", example = "AP-2610-027")
        String applicationNo,

        @Schema(description = "신청 상태", example = "SUPPLEMENT")
        ApplicationStatus status,

        @Schema(description = "신청한 스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "신청한 스토어 주소", example = "gotgan-store")
        String slug,

        @Schema(description = "반려 · 보완요청 사유 (그 밖에는 null)", example = "통신판매업 신고증 사본이 흐려 확인이 어렵습니다")
        String statusReason,

        @Schema(description = "제출 시각", example = "2026-10-22T14:05:11+09:00")
        OffsetDateTime submittedAt,

        @Schema(description = "마지막 상태 변경 시각", example = "2026-10-23T10:00:00+09:00")
        OffsetDateTime statusChangedAt,

        @Schema(description = "보완 기한 — 보완요청일 때만, 지나면 자동 반려", example = "2026-10-30T10:00:00+09:00")
        OffsetDateTime supplementDueAt,

        @Schema(description = "재신청 가능 시각 — 반려일 때만(반려 + 30일)", example = "2026-11-22T10:00:00+09:00")
        OffsetDateTime reapplyAvailableAt,

        @Schema(description = "만들어진 스토어 ID — 승인일 때만", example = "3")
        Long storeId,

        @Schema(description = "상태 이력 — 오래된 순 (접수 null → PENDING 부터)")
        List<History> history,

        @Schema(description = "다시 낼 서류 종류 — 보완요청일 때만(서류가 아닌 내용 보완이면 [], 10/29 전 보완요청은 null)", example = "[\"ECOMMERCE_REG\"]")
        List<DocType> requestedDocs,

        @Schema(description = "제출한 신청 내용 — 보완 재제출 화면이 지금 값을 채워 보여 주는 용도(스토어명 · 주소는 위 필드). 본인 신청만 조회되므로 계좌도 그대로")
        Submitted submitted) {

    /** 재제출(2-3절)로 고칠 수 있는 항목의 지금 값 + 지금 서류 목록. 필드는 추가만 한다 */
    public record Submitted(
            @Schema(description = "사업자 구분", example = "INDIVIDUAL")
            BizType bizType,

            @Schema(description = "상호", example = "곳간상회")
            String companyName,

            @Schema(description = "대표자명", example = "홍길동")
            String ownerName,

            @Schema(description = "사업자등록번호", example = "000-00-00001")
            String bizRegNo,

            @Schema(description = "통신판매업 신고번호", example = "2026-서울성동-0000")
            String ecommerceRegNo,

            @Schema(description = "담당자 휴대폰", example = "010-0000-1234")
            String managerPhone,

            @Schema(description = "담당자 이메일 — 심사 결과 메일 주소", example = "seller@example.com")
            String managerEmail,

            @Schema(description = "사업장 주소", example = "서울시 성동구 성수이로 00")
            String businessAddress,

            @Schema(description = "희망 카테고리 ID(상위)", example = "1")
            Long categoryId,

            @Schema(description = "스토어 소개 HTML(서버 정화) — 없으면 null", example = "<p>리넨 의류를 만듭니다.</p>")
            String intro,

            @Schema(description = "판매 예정 대표 상품 2~10개")
            List<RepresentativeProduct> representativeProducts,

            @Schema(description = "월 예상 매출 — 없으면 null", example = "FROM_5M_TO_20M")
            ExpectedMonthlySales expectedMonthlySales,

            @Schema(description = "운영 중인 판매 채널 — 없으면 null", example = "https://instagram.com/gotgan.store")
            String salesChannel,

            @Schema(description = "정산 계좌")
            SettlementAccount settlementAccount,

            @Schema(description = "마케팅 수신 동의", example = "false")
            boolean marketingAgreed,

            @Schema(description = "지금 서류(재제출로 바뀐 이전 서류 제외) — 종류별 · 올린 순서. 열람 주소는 없음")
            List<SubmittedDocument> documents) {
    }

    public record SubmittedDocument(
            @Schema(description = "서류 종류", example = "ECOMMERCE_REG")
            DocType docType,

            @Schema(description = "올린 파일 이름", example = "통신판매업신고증.pdf")
            String originalName,

            @Schema(description = "올린 시각", example = "2026-10-22T14:05:11+09:00")
            OffsetDateTime uploadedAt) {
    }
}
