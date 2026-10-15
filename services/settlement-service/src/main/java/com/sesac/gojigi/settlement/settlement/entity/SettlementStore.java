package com.sesac.gojigi.settlement.settlement.entity;

import com.sesac.gojigi.settlement.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정산용 스토어 스냅샷 — StoreApproved 로 생성, StoreStatusChanged 로 상태 갱신.
 * store_status 가 ACTIVE 가 아니면 지급 보류. 상태 갱신은 SettlementStoreRepository#applyStatusIfNewer 로만 한다 (옛 이벤트 무시).
 */
@Getter
@Entity
@Table(name = "settlement_store")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementStore extends BaseTimeEntity {

    public static final String ACTIVE = "ACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_store_id")
    private Long id;

    @Column(name = "store_id", nullable = false, unique = true)
    private Long storeId;

    @Column(name = "store_name", nullable = false, length = 100)
    private String storeName;

    @Column(name = "store_status", nullable = false, length = 20)
    private String storeStatus;

    @Column(name = "store_status_changed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime storeStatusChangedAt;

    private SettlementStore(Long storeId, String storeName) {
        this.storeId = storeId;
        this.storeName = storeName;
        this.storeStatus = ACTIVE;
    }

    /** StoreApproved 수신 시 — 정산 계좌 스냅샷 컬럼은 StoreApproved 구독 구현 때 채운다 */
    public static SettlementStore approved(Long storeId, String storeName) {
        return new SettlementStore(storeId, storeName);
    }

    public boolean isPayoutHeld() {
        return !ACTIVE.equals(storeStatus);
    }
}
