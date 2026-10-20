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
 * 지금은 내부 상세 조회에 필요한 컬럼만 매핑한다 — 소개 · 이미지 · 배송 정책 등은 그 API 를 만들 때 추가.
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

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;
}
