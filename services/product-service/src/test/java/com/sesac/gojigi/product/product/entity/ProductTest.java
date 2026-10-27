package com.sesac.gojigi.product.product.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 상품 엔티티 규칙 — 할인가 계산(10원 단위 반올림) · 강제 판매중지 상태 전이 (product.md 6-2 · 13절).
 */
class ProductTest {

    private static Product product(long price, int discountRate, ProductStatus status) {
        return Product.builder()
                .storeId(1L).categoryId(101L).name("리넨 셔츠")
                .price(price).discountRate(discountRate)
                .material("리넨 100%").origin("대한민국")
                .status(status)
                .build();
    }

    @ParameterizedTest(name = "{0}원 {1}% → {2}원")
    @CsvSource({
            "89000, 0, 89000",
            "89000, 15, 75650",
            "9990, 15, 8490",   // 8,491.5 → 10원 단위 반올림
            "9995, 10, 9000",   // 8,995.5 → 9,000 (5 이상 올림)
            "10000, 90, 1000"
    })
    @DisplayName("할인가는 price × (100 − 할인율) / 100 을 10원 단위로 반올림한다")
    void builder_withDiscountRate_calculatesSalePrice(long price, int discountRate, long expected) {
        // when
        Product product = product(price, discountRate, ProductStatus.ON_SALE);

        // then
        assertThat(product.getSalePrice()).isEqualTo(expected);
        assertThat(product.getDiscountRate()).isEqualByComparingTo(String.valueOf(discountRate));
    }

    @Test
    @DisplayName("새 상품은 스토어 운영중 · 삭제 안 됨으로 시작한다")
    void builder_new_startsStoreActiveAndNotDeleted() {
        // when
        Product product = product(10_000L, 0, ProductStatus.STOPPED);

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.STOPPED);
        assertThat(product.isStoreActive()).isTrue();
        assertThat(product.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("강제 판매중지하면 BLOCKED 와 사유가 저장되고, 다시 하면 사유만 바뀐다")
    void block_onSaleThenAgain_setsBlockedAndReplacesReason() {
        // given
        Product product = product(10_000L, 0, ProductStatus.ON_SALE);

        // when
        product.block("원산지 표기 누락");
        product.block("상품 정보 고시 누락");

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.BLOCKED);
        assertThat(product.getStatusReason()).isEqualTo("상품 정보 고시 누락");
    }

    @Test
    @DisplayName("강제 판매중지를 풀면 STOPPED 가 되고 사유가 지워진다")
    void unblock_blocked_becomesStoppedWithoutReason() {
        // given
        Product product = product(10_000L, 0, ProductStatus.ON_SALE);
        product.block("원산지 표기 누락");

        // when
        product.unblock();

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.STOPPED);
        assertThat(product.getStatusReason()).isNull();
    }

    @Test
    @DisplayName("BLOCKED 가 아닌 상품은 해제해도 상태가 그대로다")
    void unblock_notBlocked_keepsStatus() {
        // given
        Product product = product(10_000L, 0, ProductStatus.ON_SALE);

        // when
        product.unblock();

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }
}
