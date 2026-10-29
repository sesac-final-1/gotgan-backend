package com.sesac.gojigi.store.store.dto.response;

import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.repository.StoreWithApplicant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 관리자 스토어 목록 한 줄 (docs/api/store.md 8절). 필드는 추가만 한다 */
public record InternalStoreSummaryResponse(
        @Schema(description = "스토어 ID", example = "1")
        Long storeId,

        @Schema(description = "스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "스토어 주소", example = "gotgan-store")
        String slug,

        @Schema(description = "판매자 회원 ID", example = "2")
        Long userId,

        @Schema(description = "대표자명 — 원래 신청서", example = "홍길동")
        String ownerName,

        @Schema(description = "사업자등록번호 — 원래 신청서", example = "000-00-00001")
        String bizRegNo,

        @Schema(description = "판매자 등급", example = "SPROUT")
        StoreGrade grade,

        @Schema(description = "스토어 상태", example = "ACTIVE")
        StoreStatus status,

        @Schema(description = "정지 · 폐점 사유 (없으면 null)", example = "null")
        String statusReason,

        @Schema(description = "개설 시각(승인 시각)", example = "2026-10-15T09:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static InternalStoreSummaryResponse from(StoreWithApplicant row) {
        Store store = row.store();
        return new InternalStoreSummaryResponse(store.getId(), store.getStoreName(), store.getSlug(), store.getUserId(),
                row.ownerName(), row.bizRegNo(), store.getGrade(), store.getStatus(), store.getStatusReason(),
                store.getCreatedAt().atZone(KST).toOffsetDateTime());
    }
}
