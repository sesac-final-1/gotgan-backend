package com.sesac.gojigi.store.store.dto.response;

import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 공개 스토어 홈 — 구매자 스토어 홈 화면 (docs/api/store.md 14-2절, STR-12). 필드는 추가만 한다.
 * 정지 · 폐점이면 안내 화면용으로 storeId · storeName · status 만 채우고 나머지는 null (판매자 정보를 내보내지 않음)
 */
public record PublicStoreResponse(
        @Schema(description = "스토어 ID", example = "1")
        Long storeId,

        @Schema(description = "스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "스토어 상태 — ACTIVE 가 아니면 안내 화면(아래 값은 모두 null)", example = "ACTIVE")
        StoreStatus status,

        @Schema(description = "스토어 주소", example = "gotgan-store")
        String slug,

        @Schema(description = "카테고리 ID(상위) — 입점 신청 때 고른 값", example = "1")
        Long categoryId,

        @Schema(description = "스토어 소개 HTML(서버 정화) — 없으면 null", example = "<p>리넨 의류를 만듭니다.</p>")
        String intro,

        @Schema(description = "대표 이미지 조회 URL — 없으면 null(화면은 color 아바타)", example = "https://sesac-gojigi-images-4380.s3.ap-northeast-2.amazonaws.com/stores/1/7f3c9e2a.jpg")
        String imageUrl,

        @Schema(description = "표시 색 #RRGGBB", example = "#5b6b4a")
        String color,

        @Schema(description = "판매자 등급", example = "SPROUT")
        StoreGrade grade,

        @Schema(description = "개설 시각(승인 시각)", example = "2026-10-15T09:00:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "대표자 — 원래 신청서(판매자 정보 표시)", example = "홍길동")
        String ownerName,

        @Schema(description = "사업자등록번호 — 원래 신청서(판매자 정보 표시)", example = "000-00-00001")
        String bizRegNo,

        @Schema(description = "고객 문의 연락처", example = "010-0000-1234")
        String csPhone,

        @Schema(description = "기본 배송비 (원)", example = "3000")
        Long shippingFee,

        @Schema(description = "무료배송 기준 금액 (원) — null 이면 무료배송 없음", example = "50000")
        Long freeShippingThreshold,

        @Schema(description = "기본 택배사 — 판매자가 정하기 전엔 null", example = "CJ대한통운")
        String courier,

        @Schema(description = "출고 안내 문구 — 판매자가 정하기 전엔 null", example = "평일 14시 이전 결제 시 당일 출고")
        String dispatchNote) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static PublicStoreResponse of(Store store, StoreApplication application, String imageUrl) {
        return new PublicStoreResponse(store.getId(), store.getStoreName(), store.getStatus(), store.getSlug(),
                application.getCategoryId(), store.getIntro(), imageUrl, store.getColor(), store.getGrade(),
                store.getCreatedAt().atZone(KST).toOffsetDateTime(), application.getOwnerName(), application.getBizRegNo(),
                store.getCsPhone(), store.getShippingFee(), store.getFreeShippingThreshold(), store.getCourier(),
                store.getDispatchNote());
    }

    /** 정지 · 폐점 — 안내 화면에 쓸 이름과 상태만 */
    public static PublicStoreResponse closed(Store store) {
        return new PublicStoreResponse(store.getId(), store.getStoreName(), store.getStatus(), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }
}
