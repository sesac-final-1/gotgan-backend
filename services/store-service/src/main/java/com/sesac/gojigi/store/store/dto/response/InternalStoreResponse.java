package com.sesac.gojigi.store.store.dto.response;

import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 내부 스토어 상세 (docs/api/store.md 1절) — user-service(판매자 탈퇴 전 폐점 확인) · order-service(배송비 계산) · chat-service · admin-service(관리자 상세)가 호출.
 * userId 로 호출자가 "이 스토어가 그 판매자 것인지" 확인할 수 있다. 필드는 추가만 한다 — 앞 7개 뒤로 관리자 상세용 필드(1절 확장)를 붙였다.
 * 배송비는 order 가 스토어별 상품 합계 >= freeShippingThreshold 이면 0원, 아니면 shippingFee (threshold null = 무료배송 없음).
 */
public record InternalStoreResponse(
        @Schema(description = "스토어 ID", example = "1")
        Long storeId,

        @Schema(description = "판매자 회원 ID", example = "2")
        Long userId,

        @Schema(description = "스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "스토어 상태", example = "ACTIVE")
        StoreStatus status,

        @Schema(description = "스토어 묶음 기본 배송비 (원)", example = "3000")
        Long shippingFee,

        @Schema(description = "무료배송 기준 금액 (원) — null 이면 무료배송 없음", example = "30000")
        Long freeShippingThreshold,

        @Schema(description = "기본 택배사 — 승인 직후 null", example = "우체국택배")
        String courier,

        // ── 1절 확장 (관리자 스토어 상세) — user · order · chat 은 위 필드만 읽으면 된다

        @Schema(description = "스토어 주소", example = "gotgan-store")
        String slug,

        @Schema(description = "대표자명 — 원래 신청서", example = "홍길동")
        String ownerName,

        @Schema(description = "상호 — 원래 신청서", example = "곳간상회")
        String companyName,

        @Schema(description = "사업자등록번호 — 원래 신청서", example = "000-00-00001")
        String bizRegNo,

        @Schema(description = "고객 문의 연락처", example = "010-0000-1234")
        String csPhone,

        @Schema(description = "판매자 등급", example = "SPROUT")
        StoreGrade grade,

        @Schema(description = "출고 안내 문구 — 없으면 null", example = "평일 14시 이전 결제 시 당일 출고")
        String dispatchNote,

        @Schema(description = "가장 최근 정지 · 해제 · 폐점 사유 — 없으면 null", example = "허위 광고 신고 3건 확인")
        String statusReason,

        @Schema(description = "원래 입점 신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "개설 시각(승인 시각)", example = "2026-10-15T09:00:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "상태 이력 — 오래된 순 (개설 NULL → ACTIVE 부터)")
        List<History> statusHistory) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record History(
            @Schema(description = "이전 상태 — 개설은 null", example = "ACTIVE")
            StoreStatus fromStatus,

            @Schema(description = "바뀐 상태", example = "SUSPENDED")
            StoreStatus toStatus,

            @Schema(description = "바꾼 쪽 — 관리자 개인은 남기지 않음", example = "ADMIN")
            StoreActorType actor,

            @Schema(description = "사유 — 없으면 null", example = "허위 광고 신고 3건 확인")
            String reason,

            @Schema(description = "바뀐 시각", example = "2026-10-28T14:30:00+09:00")
            OffsetDateTime changedAt) {
    }

    public static InternalStoreResponse of(Store store, StoreApplication application, List<StoreStatusHistory> histories) {
        return new InternalStoreResponse(store.getId(), store.getUserId(), store.getStoreName(), store.getStatus(),
                store.getShippingFee(), store.getFreeShippingThreshold(), store.getCourier(),
                store.getSlug(), application.getOwnerName(), application.getCompanyName(), application.getBizRegNo(),
                store.getCsPhone(), store.getGrade(), store.getDispatchNote(), store.getStatusReason(),
                store.getStoreApplicationId(), kst(store.getCreatedAt()),
                histories.stream()
                        .map(h -> new History(h.getFromStatus(), h.getToStatus(), h.getActorType(), h.getReason(), kst(h.getCreatedAt())))
                        .toList());
    }

    private static OffsetDateTime kst(LocalDateTime dateTime) {
        return dateTime.atZone(KST).toOffsetDateTime();
    }
}
