package com.sesac.gojigi.store.store.dto.response;

import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.repository.StoreWithCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 공개 스토어 목록 한 줄 — 구매자 스토어 목록 화면 (docs/api/store.md 14절, STR-12). 필드는 추가만 한다 */
public record PublicStoreSummaryResponse(
        @Schema(description = "스토어 ID — 스토어 홈 경로 /api/stores/{storeId}", example = "1")
        Long storeId,

        @Schema(description = "스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "스토어 주소", example = "gotgan-store")
        String slug,

        @Schema(description = "카테고리 ID(상위) — 입점 신청 때 고른 값, 이름은 카테고리 API 로", example = "1")
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
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static PublicStoreSummaryResponse of(StoreWithCategory row, String imageUrl) {
        Store store = row.store();
        return new PublicStoreSummaryResponse(store.getId(), store.getStoreName(), store.getSlug(), row.categoryId(),
                store.getIntro(), imageUrl, store.getColor(), store.getGrade(), store.getCreatedAt().atZone(KST).toOffsetDateTime());
    }
}
