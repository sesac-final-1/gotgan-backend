package com.sesac.gojigi.product.store.entity;

import com.sesac.gojigi.product.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스토어 운영 상태 스냅샷 (productdb store_status, V3) — StoreStatusChanged 로만 바뀐다.
 * 상품이 없을 때 온 이벤트도 기억해, 그 뒤 등록하는 상품의 is_store_active 초기값이 된다 (product.md "노출 · 구매 가능").
 */
@Getter
@Entity
@Table(name = "store_status")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreStatus extends BaseTimeEntity {

    private static final String ACTIVE = "ACTIVE";

    @Id
    @Column(name = "store_id")
    private Long storeId;

    /** 문자열 그대로 — store 가 값을 늘려도 ACTIVE 가 아니면 숨김(product.md 16) */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "status_changed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime statusChangedAt;

    public static StoreStatus of(Long storeId, String status, LocalDateTime changedAt) {
        StoreStatus storeStatus = new StoreStatus();
        storeStatus.storeId = storeId;
        storeStatus.status = status;
        storeStatus.statusChangedAt = changedAt;
        return storeStatus;
    }

    /** ACTIVE 일 때만 운영 중 — 행이 없는 스토어도 운영 중으로 본다(호출하는 쪽) */
    public static boolean isActive(String status) {
        return ACTIVE.equals(status);
    }

    public boolean isActive() {
        return isActive(status);
    }

    /** 이 이벤트가 마지막으로 반영한 것보다 새로우면 반영하고 true, 같거나 옛 이벤트면 false (events.md A안) */
    public boolean applyIfNewer(String newStatus, LocalDateTime changedAt) {
        if (!changedAt.isAfter(statusChangedAt)) {
            return false;
        }
        this.status = newStatus;
        this.statusChangedAt = changedAt;
        return true;
    }
}
