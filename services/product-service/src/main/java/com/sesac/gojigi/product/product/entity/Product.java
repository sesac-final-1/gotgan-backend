package com.sesac.gojigi.product.product.entity;

import com.sesac.gojigi.product.common.entity.BaseTimeEntity;
import com.sesac.gojigi.product.image.entity.ProductImage;
import com.sesac.gojigi.product.option.entity.ProductOption;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 상품 (productdb product). 옵션 조합 · 이미지는 상품이 함께 저장한다 (등록 · 수정이 통째로 오는 13절 계약).
 * 카테고리는 categoryId 로만 들고, 하위 · 사용 중 검증은 서비스가 CategoryRepository 로 한다 (CAT-03).
 * 삭제는 deleted_at 소프트 삭제 — 지난 주문 · 재고 이력이 참조하므로 전역 필터(@SQLRestriction)는 걸지 않고 조회 쿼리에서 거른다.
 * @DynamicUpdate — 바뀐 컬럼만 UPDATE. 판매자 수정이 그 사이 관리자가 바꾼 status · 이벤트가 바꾼 is_store_active 를 덮어쓰지 않게 (13절 "수정은 상태를 바꾸지 않음").
 */
@Getter
@Entity
@DynamicUpdate
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price", nullable = false)
    private long price;

    /** 즉시할인율 % (0~90) — API 는 정수, 컬럼이 DECIMAL(5,2) 라 BigDecimal */
    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    /** price · discountRate 로 서버가 계산 (10원 단위 반올림) — 밖에서 직접 넣지 않는다 */
    @Column(name = "sale_price", nullable = false)
    private long salePrice;

    /** sanitizer 로 정화한 HTML */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "material", nullable = false, length = 100)
    private String material;

    @Column(name = "origin", nullable = false, length = 50)
    private String origin;

    /** 옵션 그룹 이름 — 옵션 없는 상품은 둘 다 null */
    @Column(name = "option1_name", length = 30)
    private String option1Name;

    @Column(name = "option2_name", length = 30)
    private String option2Name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status;

    /** 강제 판매중지 사유 (BLOCKED 일 때) */
    @Column(name = "status_reason")
    private String statusReason;

    /** StoreStatusChanged 로 갱신 — false 면 목록 · 검색에서 제외 */
    @Column(name = "is_store_active", nullable = false)
    private boolean storeActive;

    @Column(name = "store_status_changed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime storeStatusChangedAt;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    /** 사라진 조합은 소프트 삭제라 컬렉션에서 빼지 않는다 (orphanRemoval 없음). 등록 순서(ID) — 상세 화면 조합표 순서 */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<ProductOption> options = new ArrayList<>();

    /** 참조하는 곳이 없어 빠진 이미지는 행 삭제 (orphanRemoval) — sortOrder 0 이 대표 */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    /** 등록 — 판매 상태는 ON_SALE · STOPPED 중 요청 값, 스토어는 운영중으로 시작 */
    @Builder
    private Product(Long storeId, Long categoryId, String name, long price, int discountRate, String description,
            String material, String origin, String option1Name, String option2Name, ProductStatus status) {
        this.storeId = storeId;
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.material = material;
        this.origin = origin;
        this.option1Name = option1Name;
        this.option2Name = option2Name;
        this.status = status;
        this.storeActive = true;
        changePrice(price, discountRate);
    }

    public void addOption(ProductOption option) {
        options.add(option);
        option.assignProduct(this);
    }

    public void addImage(ProductImage image) {
        images.add(image);
        image.assignProduct(this);
    }

    /** 수정 (13절 PUT) — 판매 상태 · 재고는 건드리지 않는다. 판매가는 다시 계산 */
    public void update(Long categoryId, String name, long price, int discountRate, String description,
            String material, String origin, String option1Name, String option2Name) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.material = material;
        this.origin = origin;
        this.option1Name = option1Name;
        this.option2Name = option2Name;
        changePrice(price, discountRate);
    }

    /** 이미지 다시 저장 — 요청 순서가 sortOrder(0 이 대표), 빠진 이미지 행은 orphanRemoval 로 삭제 (S3 객체는 남음) */
    public void replaceImages(List<String> s3Keys) {
        images.clear();
        for (int i = 0; i < s3Keys.size(); i++) {
            addImage(ProductImage.of(s3Keys.get(i), i));
        }
    }

    /** 관리자 강제 판매중지 (6-2절) — 이미 BLOCKED 면 사유만 바꾼다. 사유 필수 검사는 요청 DTO 몫 */
    public void block(String reason) {
        this.status = ProductStatus.BLOCKED;
        this.statusReason = reason;
    }

    /** 강제 판매중지 해제 — 바로 팔리지 않도록 STOPPED 로 돌리고 판매자가 점검 후 재개. BLOCKED 가 아니면 그대로 */
    public void unblock() {
        if (status != ProductStatus.BLOCKED) {
            return;
        }
        this.status = ProductStatus.STOPPED;
        this.statusReason = null;
    }

    /** 소프트 삭제 (16절) — 옵션 · 재고 이력은 그대로 둔다. 시각은 서비스가 Clock 으로 넘긴다 */
    public void delete(LocalDateTime now) {
        this.deletedAt = now;
    }

    private void changePrice(long price, int discountRate) {
        this.price = price;
        this.discountRate = BigDecimal.valueOf(discountRate).setScale(2); // 컬럼 DECIMAL(5,2) 와 같은 자릿수 — 등록 응답도 다시 읽은 값처럼 15.00
        this.salePrice = calculateSalePrice(price, discountRate);
    }

    /** price × (100 − 할인율) / 100 을 10원 단위 반올림 — 예: 89,000원 15% → 75,650원, 9,990원 15% → 8,490원 */
    static long calculateSalePrice(long price, int discountRate) {
        return BigDecimal.valueOf(price)
                .multiply(BigDecimal.valueOf(100L - discountRate))
                .movePointLeft(2)
                .setScale(-1, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
