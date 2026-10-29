package com.sesac.gojigi.store.store.dto.response;

import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 판매자 내 스토어 — 스토어 설정 화면 (docs/api/store.md 13절, STR-06). 필드는 추가만 한다 */
public record SellerStoreResponse(
        @Schema(description = "스토어 ID", example = "1")
        Long storeId,

        @Schema(description = "스토어명 — 변경은 관리자 승인 필요(P2), 이 화면에서는 읽기만", example = "곳간상회")
        String storeName,

        @Schema(description = "스토어 주소 — 변경 불가", example = "gotgan-store")
        String slug,

        @Schema(description = "스토어 상태", example = "ACTIVE")
        StoreStatus status,

        @Schema(description = "정지 사유 — 없으면 null", example = "null")
        String statusReason,

        @Schema(description = "스토어 소개 HTML — 없으면 null", example = "<p>리넨 의류를 만듭니다.</p>")
        String intro,

        @Schema(description = "대표 이미지 S3 키 — 없으면 null (수정 때 그대로 보내면 유지)", example = "stores/1/7f3c9e2a.jpg")
        String imageKey,

        @Schema(description = "대표 이미지 조회 URL — 없으면 null(화면은 color 아바타)", example = "https://sesac-gojigi-images-4380.s3.ap-northeast-2.amazonaws.com/stores/1/7f3c9e2a.jpg")
        String imageUrl,

        @Schema(description = "표시 색 #RRGGBB", example = "#5b6b4a")
        String color,

        @Schema(description = "고객 문의 연락처", example = "010-0000-1234")
        String csPhone,

        @Schema(description = "기본 배송비 (원)", example = "3000")
        Long shippingFee,

        @Schema(description = "무료배송 기준 금액 (원) — null 이면 무료배송 없음", example = "50000")
        Long freeShippingThreshold,

        @Schema(description = "기본 택배사 — 승인 직후 null", example = "CJ대한통운")
        String courier,

        @Schema(description = "출고 안내 문구 — 승인 직후 null", example = "평일 14시 이전 결제 시 당일 출고")
        String dispatchNote,

        @Schema(description = "판매자 등급", example = "SPROUT")
        StoreGrade grade,

        @Schema(description = "대표자명 — 원래 신청서, 변경은 재심사", example = "홍길동")
        String ownerName,

        @Schema(description = "사업자등록번호 — 원래 신청서, 변경은 재심사", example = "000-00-00001")
        String bizRegNo,

        @Schema(description = "개설 시각(승인 시각)", example = "2026-10-15T09:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static SellerStoreResponse of(Store store, StoreApplication application, String imageUrl) {
        return new SellerStoreResponse(store.getId(), store.getStoreName(), store.getSlug(), store.getStatus(),
                store.getStatusReason(), store.getIntro(), store.getImageKey(), imageUrl, store.getColor(), store.getCsPhone(),
                store.getShippingFee(), store.getFreeShippingThreshold(), store.getCourier(), store.getDispatchNote(),
                store.getGrade(), application.getOwnerName(), application.getBizRegNo(),
                store.getCreatedAt().atZone(KST).toOffsetDateTime());
    }
}
