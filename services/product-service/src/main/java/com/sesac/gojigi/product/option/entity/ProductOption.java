package com.sesac.gojigi.product.option.entity;

import com.sesac.gojigi.product.common.entity.BaseTimeEntity;
import com.sesac.gojigi.product.product.entity.Product;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 옵션 조합 · 재고 (productdb product_option). 조합 1개 = 1행, 옵션 없는 상품도 값이 null 인 기본 조합 1행.
 * 재고 차감은 엔티티가 아니라 저장소의 조건부 UPDATE(stock >= :qty) 로 한다 — 동시 주문에서 읽고-쓰기 경합을 피하려고 (재고 예약 PR).
 * 구성 변경으로 사라진 조합은 소프트 삭제 — 삭제된 조합도 재고 복원 대상이라 전역 필터를 걸지 않는다.
 */
@Getter
@Entity
@Table(name = "product_option")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_option_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "option1_value", length = 30)
    private String option1Value;

    @Column(name = "option2_value", length = 30)
    private String option2Value;

    /** 최종가 = product.salePrice + extraPrice */
    @Column(name = "extra_price", nullable = false)
    private long extraPrice;

    @Column(name = "stock", nullable = false, columnDefinition = "INT UNSIGNED")
    private int stock;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    /** 새 조합 — 상품에는 Product.addOption 으로 붙인다 */
    public static ProductOption of(String option1Value, String option2Value, long extraPrice, int stock) {
        ProductOption option = new ProductOption();
        option.option1Value = option1Value;
        option.option2Value = option2Value;
        option.extraPrice = extraPrice;
        option.stock = stock;
        return option;
    }

    /** Product.addOption 에서만 부른다 (양방향 연결) */
    public void assignProduct(Product product) {
        this.product = product;
    }
}
