package com.sesac.gojigi.store.store.entity;

import com.sesac.gojigi.store.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스토어 · 배송 정책 (storedb store). 입점 승인으로 생성, 1인 1스토어(uk_store_user_id).
 * 지금은 내부 상세 조회에 필요한 컬럼만 매핑한다 — 배송 정책은 order 배송비 계산용 3개만, 소개 · 이미지 등은 그 API 를 만들 때 추가.
 * 폐점은 status = CLOSED 로 표현하고 deleted_at 은 채우지 않는다 (폐점 스토어도 주문 · 정산 내역에서 조회돼야 함).
 */
@Getter
@Entity
@Table(name = "store")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long id;

    @Column(name = "store_application_id", nullable = false, unique = true)
    private Long storeApplicationId;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "store_name", nullable = false, length = 50)
    private String storeName;

    @Column(name = "slug", nullable = false, length = 50)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StoreStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    /** 스토어 묶음 기본 배송비 (원) — 승인 직후 3,000원 */
    @Column(name = "shipping_fee", nullable = false)
    private Long shippingFee;

    /** 무료배송 기준 금액 (원) — null 이면 무료배송 없음 */
    @Column(name = "free_shipping_threshold")
    private Long freeShippingThreshold;

    /** 기본 택배사 이름 — 승인 직후 null */
    @Column(name = "courier", length = 30)
    private String courier;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;
}
