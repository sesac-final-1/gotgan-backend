package com.sesac.gojigi.store.apply.entity;

import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 입점 신청서 (storedb store_application). 심사 당시 기록이라 승인 후에도 내용은 고치지 않는다 — 상태만 바뀐다.
 * 진행 중 신청 1건 · 1인 1스토어는 서비스 검증 (docs/api/store.md 2절).
 */
@Getter
@Entity
@Table(name = "store_application")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreApplication extends BaseTimeEntity {

    /** 보완요청 후 재제출 기한 (store.md 7절, STR-09) */
    public static final int SUPPLEMENT_DUE_DAYS = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_application_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "biz_type", nullable = false, length = 20)
    private BizType bizType;

    @Column(name = "company_name", nullable = false, length = 100)
    private String companyName;

    @Column(name = "owner_name", nullable = false, length = 50)
    private String ownerName;

    @Column(name = "biz_reg_no", nullable = false, length = 12)
    private String bizRegNo;

    @Column(name = "ecommerce_reg_no", nullable = false, length = 50)
    private String ecommerceRegNo;

    @Column(name = "manager_phone", nullable = false, length = 20)
    private String managerPhone;

    @Column(name = "manager_email", nullable = false, length = 100)
    private String managerEmail;

    @Column(name = "business_address", nullable = false)
    private String businessAddress;

    @Column(name = "store_name", nullable = false, length = 50)
    private String storeName;

    @Column(name = "slug", nullable = false, length = 50)
    private String slug;

    @Column(name = "intro", columnDefinition = "TEXT")
    private String intro;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "representative_products", nullable = false)
    private List<RepresentativeProduct> representativeProducts;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_monthly_sales", length = 20)
    private ExpectedMonthlySales expectedMonthlySales;

    @Column(name = "sales_channel")
    private String salesChannel;

    @Column(name = "bank_name", nullable = false, length = 30)
    private String bankName;

    @Column(name = "account_no", nullable = false, length = 30)
    private String accountNo;

    @Column(name = "account_holder", nullable = false, length = 50)
    private String accountHolder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApplicationStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "marketing_agreed", nullable = false)
    private boolean marketingAgreed;

    @Column(name = "terms_agreed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime termsAgreedAt;

    @Column(name = "status_changed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime statusChangedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 제출 — 심사대기로 시작하고, 필수 약관 동의 시각 · 상태 변경 시각은 제출 시각 (서버 Clock) */
    @Builder
    private StoreApplication(Long userId, Long categoryId, BizType bizType, String companyName, String ownerName,
            String bizRegNo, String ecommerceRegNo, String managerPhone, String managerEmail, String businessAddress,
            String storeName, String slug, String intro, List<RepresentativeProduct> representativeProducts,
            ExpectedMonthlySales expectedMonthlySales, String salesChannel, String bankName, String accountNo,
            String accountHolder, boolean marketingAgreed, LocalDateTime submittedAt) {
        this.userId = userId;
        this.categoryId = categoryId;
        this.bizType = bizType;
        this.companyName = companyName;
        this.ownerName = ownerName;
        this.bizRegNo = bizRegNo;
        this.ecommerceRegNo = ecommerceRegNo;
        this.managerPhone = managerPhone;
        this.managerEmail = managerEmail;
        this.businessAddress = businessAddress;
        this.storeName = storeName;
        this.slug = slug;
        this.intro = intro;
        this.representativeProducts = representativeProducts;
        this.expectedMonthlySales = expectedMonthlySales;
        this.salesChannel = salesChannel;
        this.bankName = bankName;
        this.accountNo = accountNo;
        this.accountHolder = accountHolder;
        this.status = ApplicationStatus.PENDING;
        this.marketingAgreed = marketingAgreed;
        this.termsAgreedAt = submittedAt;
        this.statusChangedAt = submittedAt;
    }

    /** 승인 — 심사대기 · 보완요청에서만. 다른 상태면 409 (다른 관리자가 먼저 처리함, store.md 5절) */
    public void approve(LocalDateTime decidedAt) {
        requireUndecided();
        this.status = ApplicationStatus.APPROVED;
        this.statusReason = null; // 보완요청 사유가 승인 뒤에도 남아 보이지 않게
        this.statusChangedAt = decidedAt;
    }

    /** 반려 — 승인과 같은 조건. 사유는 신청자 화면 · 메일에 그대로 보이고, 30일 재신청 제한은 이 시각부터 (store.md 6절) */
    public void reject(String reason, LocalDateTime decidedAt) {
        requireUndecided();
        this.status = ApplicationStatus.REJECTED;
        this.statusReason = reason;
        this.statusChangedAt = decidedAt;
    }

    /**
     * 보완요청 — 심사대기에서만(보완요청 중이면 재제출을 기다림, 409). 메시지는 신청자 화면 · 메일에 보인다 (store.md 7절).
     * 기한은 저장하지 않고 상태 변경 시각 + 7일로 계산 — 7일 자동 반려도 같은 기준
     */
    public void requestSupplement(String message, LocalDateTime requestedAt) {
        if (status != ApplicationStatus.PENDING) {
            throw new ApplyException(ApplyErrorCode.APPLY_INVALID_STATUS);
        }
        this.status = ApplicationStatus.SUPPLEMENT;
        this.statusReason = message;
        this.statusChangedAt = requestedAt;
    }

    /** 보완 기한 — 보완요청 중일 때만 의미 있음 */
    public LocalDateTime supplementDueAt() {
        return statusChangedAt.plusDays(SUPPLEMENT_DUE_DAYS);
    }

    private void requireUndecided() {
        if (status != ApplicationStatus.PENDING && status != ApplicationStatus.SUPPLEMENT) {
            throw new ApplyException(ApplyErrorCode.APPLY_INVALID_STATUS);
        }
    }
}
