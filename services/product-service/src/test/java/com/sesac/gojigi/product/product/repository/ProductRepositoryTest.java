package com.sesac.gojigi.product.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.image.entity.ProductImage;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 상품 · 옵션 조합 · 이미지 ↔ productdb 스키마(Flyway V1~V2) 매핑 — 컨텍스트가 뜨면 ddl-auto: validate 통과
 * (stock INT UNSIGNED · description TEXT · discount_rate DECIMAL 포함). 저장 후 영속성 컨텍스트를 비우고 다시 읽는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ProductRepository productRepository;

    private Long categoryId;

    @BeforeEach
    void setUp() {
        Category top = em.persist(Category.top("패션의류", "Apparel", 1));
        categoryId = em.persist(Category.child(top.getId(), "셔츠", null, 1)).getId();
    }

    private Product product(String option1Name, String option2Name) {
        return Product.builder()
                .storeId(1L).categoryId(categoryId).name("프렌치 리넨 오버핏 셔츠")
                .price(89_000L).discountRate(15)
                .description("<p>여름 셔츠</p>").material("리넨 100%").origin("대한민국")
                .option1Name(option1Name).option2Name(option2Name)
                .status(ProductStatus.ON_SALE)
                .build();
    }

    @Test
    @DisplayName("상품 · 옵션 2개 · 이미지 2개를 함께 저장하고 다시 읽는다 (이미지는 sortOrder 순)")
    void save_withOptionsAndImages_persistsAllByCascade() {
        // given
        Product product = product("컬러", "사이즈");
        product.addOption(ProductOption.of("오트밀", "M", 0L, 30));
        product.addOption(ProductOption.of("오트밀", "L", 2_000L, 0));
        product.addImage(ProductImage.of("products/1/b.jpg", 1));
        product.addImage(ProductImage.of("products/1/a.jpg", 0));

        // when
        Long id = productRepository.save(product).getId();
        em.flush();
        em.clear();
        Product found = productRepository.findById(id).orElseThrow();

        // then
        assertThat(found.getSalePrice()).isEqualTo(75_650L);
        assertThat(found.getDiscountRate()).isEqualByComparingTo("15");
        assertThat(found.getDescription()).isEqualTo("<p>여름 셔츠</p>");
        assertThat(found.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(found.isStoreActive()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getOptions())
                .extracting(ProductOption::getOption1Value, ProductOption::getOption2Value,
                        ProductOption::getExtraPrice, ProductOption::getStock)
                .containsExactlyInAnyOrder(tuple("오트밀", "M", 0L, 30), tuple("오트밀", "L", 2_000L, 0));
        assertThat(found.getOptions()).allSatisfy(o -> assertThat(o.getProduct().getId()).isEqualTo(id));
        assertThat(found.getImages()).extracting(ProductImage::getS3Key)
                .containsExactly("products/1/a.jpg", "products/1/b.jpg");
    }

    @Test
    @DisplayName("옵션 없는 상품은 값이 null 인 기본 조합 1행으로 저장된다")
    void save_withoutOptionGroups_persistsDefaultOption() {
        // given
        Product product = product(null, null);
        product.addOption(ProductOption.of(null, null, 0L, 10));
        product.addImage(ProductImage.of("products/1/a.jpg", 0));

        // when
        Long id = productRepository.save(product).getId();
        em.flush();
        em.clear();
        Product found = productRepository.findById(id).orElseThrow();

        // then
        assertThat(found.getOption1Name()).isNull();
        assertThat(found.getOptions()).singleElement()
                .satisfies(o -> {
                    assertThat(o.getOption1Value()).isNull();
                    assertThat(o.getOption2Value()).isNull();
                    assertThat(o.getStock()).isEqualTo(10);
                });
    }

    @Test
    @DisplayName("삭제한 상품은 findByIdAndDeletedAtIsNull 에서 빠지고 findById 로는 계속 읽힌다")
    void findByIdAndDeletedAtIsNull_deleted_returnsEmpty() {
        // given
        Product product = product(null, null);
        product.addOption(ProductOption.of(null, null, 0L, 10));
        Long id = productRepository.save(product).getId();
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 10, 0);

        // when
        product.delete(now);
        em.flush();
        em.clear();

        // then
        assertThat(productRepository.findByIdAndDeletedAtIsNull(id)).isEmpty();
        assertThat(productRepository.findById(id)).get()
                .extracting(Product::getDeletedAt).isEqualTo(now);
    }

    @Test
    @DisplayName("삭제 안 된 상품은 findByIdAndDeletedAtIsNull 로 읽힌다")
    void findByIdAndDeletedAtIsNull_notDeleted_returnsProduct() {
        // given
        Product product = product(null, null);
        product.addOption(ProductOption.of(null, null, 0L, 10));
        Long id = productRepository.save(product).getId();
        em.flush();
        em.clear();

        // when · then
        assertThat(productRepository.findByIdAndDeletedAtIsNull(id)).isPresent();
    }
}
