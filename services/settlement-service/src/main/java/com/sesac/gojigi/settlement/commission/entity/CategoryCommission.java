package com.sesac.gojigi.settlement.commission.entity;

import com.sesac.gojigi.settlement.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상위 카테고리 판매수수료율 — 바꿀 때는 새 행(적용 시작일)을 추가하고 옛 행은 이력으로 남긴다.
 * 정산 적재 때 구매확정일에 유효한 행(effective_from ≤ 구매확정일 중 가장 늦은 것)의 율을 settlement_item 에 스냅샷.
 */
@Getter
@Entity
@Table(name = "category_commission")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryCommission extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_commission_id")
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** % 값 — 11.00 = 11% (컨벤션 타입 관례: 비율은 % · DECIMAL(5,2)) */
    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    private CategoryCommission(Long categoryId, BigDecimal commissionRate, LocalDate effectiveFrom) {
        this.categoryId = categoryId;
        this.commissionRate = commissionRate;
        this.effectiveFrom = effectiveFrom;
    }

    public static CategoryCommission of(Long categoryId, BigDecimal commissionRate, LocalDate effectiveFrom) {
        return new CategoryCommission(categoryId, commissionRate, effectiveFrom);
    }
}
