package com.sesac.gojigi.user.address.entity;

import com.sesac.gojigi.user.common.entity.BaseTimeEntity;
import com.sesac.gojigi.user.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 배송지 — address → user 단방향 (컨벤션 2-1). 소프트 삭제.
 * 기본 배송지는 회원당 1개 — 새 기본 지정 시 기존 기본 해제를 같은 트랜잭션에서 (서비스 계층)
 */
@Getter
@Entity
@Table(name = "address")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "address_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "label", nullable = false, length = 30)
    private String label;

    @Column(name = "receiver_name", nullable = false, length = 50)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "zipcode", nullable = false, length = 10)
    private String zipcode;

    @Column(name = "address1", nullable = false, length = 200)
    private String address1;

    @Column(name = "address2", length = 200)
    private String address2;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    @Builder
    private Address(User user, String label, String receiverName, String receiverPhone,
                    String zipcode, String address1, String address2, boolean isDefault) {
        this.user = user;
        this.label = label;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.zipcode = zipcode;
        this.address1 = address1;
        this.address2 = address2;
        this.isDefault = isDefault;
    }

    public void update(String label, String receiverName, String receiverPhone,
                       String zipcode, String address1, String address2) {
        this.label = label;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.zipcode = zipcode;
        this.address1 = address1;
        this.address2 = address2;
    }

    public void markDefault() {
        this.isDefault = true;
    }

    public void unmarkDefault() {
        this.isDefault = false;
    }

    /** 물리 DELETE 금지 — 기본 배송지였다면 해제도 함께 */
    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        this.isDefault = false;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 소유권 검사 — 다른 회원의 배송지 접근 차단 (프록시 초기화 없이 FK 값만 비교) */
    public boolean isOwnedBy(Long userId) {
        return Objects.equals(user.getId(), userId);
    }
}
