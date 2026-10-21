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
 * 정산용 스토어 스냅샷 — StoreApproved 로 생성(스토어명 · 판매자 이메일 · 정산 계좌), StoreStatusChanged 로 상태 갱신.
 * store_status 가 ACTIVE 가 아니면 지급 보류. 상태 갱신은 SettlementStoreRepository#applyStatusIfNewer 로만 한다 (옛 이벤트 무시).
 * store 를 동기 호출하지 않으려고(장애 격리) 지급 · 명세서에 필요한 값을 여기 들고 있는다.
 */
@Getter
@Entity
@Table(name = "settlement_store")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementStore extends BaseTimeEntity {

    public static final String ACTIVE = "ACTIVE";
    /** 폐점 — 되돌릴 수 없음 (store 10/21 결정: status = CLOSED). 지급 보류는 ACTIVE 가 아닌 다른 상태와 같게 처리 */
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_store_id")
    private Long id;

    @Column(name = "store_id", nullable = false, unique = true)
    private Long storeId;

    @Column(name = "store_name", nullable = false, length = 100)
    private String storeName;

    @Column(name = "seller_email", length = 100)
    private String sellerEmail;

    @Column(name = "bank_name", length = 50)
    private String bankName;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "account_holder", length = 50)
    private String accountHolder;

    @Column(name = "store_status", nullable = false, length = 20)
    private String storeStatus;

    @Column(name = "store_status_changed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime storeStatusChangedAt;

    private SettlementStore(Long storeId, String storeName, String sellerEmail, String bankName, String accountNumber,
            String accountHolder) {
        this.storeId = storeId;
        this.storeName = storeName;
        this.sellerEmail = sellerEmail;
        this.bankName = bankName;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.storeStatus = ACTIVE;
    }

    /** StoreApproved 수신 — 승인 직후 스토어는 ACTIVE */
    public static SettlementStore approved(Long storeId, String storeName, String sellerEmail, String bankName,
            String accountNumber, String accountHolder) {
        return new SettlementStore(storeId, storeName, sellerEmail, bankName, accountNumber, accountHolder);
    }

    public boolean isPayoutHeld() {
        return !ACTIVE.equals(storeStatus);
    }
}
