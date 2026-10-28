package com.sesac.gojigi.product.product.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.entity.ProductImage;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 공개 상품 조회 (GET /api/products/{id}, product.md 10절) — 실제 MySQL · Flyway, 헤더 없이(로그인 없음) 부른다.
 * 상품은 저장소로 직접 만들고, 판매중지 · 삭제 · 스토어 비활성은 JdbcTemplate 으로 바꾼다 (InternalProductApiIntegrationTest 와 같음).
 * 트레이스 샘플링 0 — Zipkin 전송 실패 WARN 이 LogbackConfigTest 가짜 Loki 에 섞이지 않게.
 */
@SpringBootTest(properties = {"management.tracing.sampling.probability=0", "app.images.base-url=https://img.test/"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ProductApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired JdbcTemplate jdbc;

    private Category top;
    private Category shirt;

    @BeforeEach
    void setUp() {
        clean();
        top = Category.top("패션의류", "Apparel", 1);
        top.changeActive(true);
        top = categoryRepository.save(top);
        shirt = categoryRepository.save(Category.child(top.getId(), "셔츠", null, 1));
    }

    /** 끝나고도 지운다 — 같은 DB 를 쓰는 다른 테스트가 카테고리를 지울 때 상품 FK 에 걸리지 않게 */
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM product_image");
        jdbc.update("DELETE FROM product_option");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM category WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM category");
    }

    /** 89,000원 15% → 판매가 75,650원, 이미지 2장 */
    private Product product(ProductStatus status, ProductOption... options) {
        boolean grouped = options[0].getOption1Value() != null;
        Product product = Product.builder()
                .storeId(1L).categoryId(shirt.getId()).name("리넨 셔츠")
                .price(89_000L).discountRate(15)
                .description("<p>시원한 리넨</p>").material("리넨 100%").origin("대한민국")
                .option1Name(grouped ? "컬러" : null).option2Name(grouped ? "사이즈" : null)
                .status(status)
                .build();
        for (ProductOption option : options) {
            product.addOption(option);
        }
        product.addImage(ProductImage.of("products/1/main.jpg", 0));
        product.addImage(ProductImage.of("products/1/second.jpg", 1));
        return productRepository.save(product);
    }

    private ResultActions detail(long productId) throws Exception {
        return mockMvc.perform(get("/api/products/" + productId));
    }

    @Nested
    @DisplayName("상품 상세")
    class Detail {

        @Test
        @DisplayName("판매 중 — 200, 이미지 URL 배열 · 옵션별 품절 · 삭제된 조합 빠짐 · 판매자용 값(재고 수 · 상태 · 이미지 key) 없음")
        void get_onSale_returnsPublicFields() throws Exception {
            // given — M 30 · L 0 · S(삭제됨)
            Product p = product(ProductStatus.ON_SALE,
                    ProductOption.of("오트밀", "M", 0, 30), ProductOption.of("오트밀", "L", 2000, 0), ProductOption.of("오트밀", "S", 0, 5));
            long deletedOption = p.getOptions().get(2).getId();
            jdbc.update("UPDATE product_option SET deleted_at = NOW(6) WHERE product_option_id = ?", deletedOption);

            // when
            detail(p.getId())
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productId").value(p.getId()))
                    .andExpect(jsonPath("$.data.storeId").value(1))
                    .andExpect(jsonPath("$.data.categoryName").value("셔츠"))
                    .andExpect(jsonPath("$.data.topCategoryId").value(top.getId()))
                    .andExpect(jsonPath("$.data.topCategoryName").value("패션의류"))
                    .andExpect(jsonPath("$.data.salePrice").value(75650))
                    .andExpect(jsonPath("$.data.description").value("<p>시원한 리넨</p>"))
                    .andExpect(jsonPath("$.data.images[0]").value("https://img.test/products/1/main.jpg"))
                    .andExpect(jsonPath("$.data.images[1]").value("https://img.test/products/1/second.jpg"))
                    .andExpect(jsonPath("$.data.options.length()").value(2))
                    .andExpect(jsonPath("$.data.options[0].option2Value").value("M"))
                    .andExpect(jsonPath("$.data.options[0].soldOut").value(false))
                    .andExpect(jsonPath("$.data.options[1].extraPrice").value(2000))
                    .andExpect(jsonPath("$.data.options[1].soldOut").value(true))
                    .andExpect(jsonPath("$.data.purchasable").value(true))
                    .andExpect(jsonPath("$.data.soldOut").value(false))
                    .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                    // 판매자만 보는 값은 공개 응답에 없다
                    .andExpect(jsonPath("$.data.options[0].stock").doesNotExist())
                    .andExpect(jsonPath("$.data.status").doesNotExist())
                    .andExpect(jsonPath("$.data.statusReason").doesNotExist())
                    .andExpect(jsonPath("$.data.hiddenReason").doesNotExist())
                    .andExpect(jsonPath("$.data.images[0].key").doesNotExist());
        }

        @Test
        @DisplayName("옵션 없는 상품 — 값이 null 인 옵션 1개 (장바구니는 이 productOptionId 로)")
        void get_noOptionGroup_singleNullOption() throws Exception {
            Product p = product(ProductStatus.ON_SALE, ProductOption.of(null, null, 0, 3));

            detail(p.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.option1Name").doesNotExist())
                    .andExpect(jsonPath("$.data.options.length()").value(1))
                    .andExpect(jsonPath("$.data.options[0].productOptionId").value(p.getOptions().getFirst().getId()))
                    .andExpect(jsonPath("$.data.options[0].option1Value").doesNotExist());
        }

        @Test
        @DisplayName("모든 옵션 재고 0 — 품절이지만 구매 가능 판단은 따로 (purchasable true · soldOut true)")
        void get_allSoldOut_soldOutButPurchasable() throws Exception {
            Product p = product(ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 0));

            detail(p.getId())
                    .andExpect(jsonPath("$.data.soldOut").value(true))
                    .andExpect(jsonPath("$.data.purchasable").value(true));
        }

        @Test
        @DisplayName("판매중지 · 강제 판매중지 · 카테고리 꺼짐 · 스토어 비활성 — 200 이지만 purchasable false")
        void get_notPurchasable_stillShown() throws Exception {
            // given
            long stopped = product(ProductStatus.STOPPED, ProductOption.of("오트밀", "M", 0, 1)).getId();
            long blocked = product(ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 1)).getId();
            jdbc.update("UPDATE product SET status = 'BLOCKED', status_reason = '허위 표시' WHERE product_id = ?", blocked);
            long storeOff = product(ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 1)).getId();
            jdbc.update("UPDATE product SET is_store_active = FALSE WHERE product_id = ?", storeOff);

            // when · then
            for (long id : new long[] {stopped, blocked, storeOff}) {
                detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.purchasable").value(false));
            }
            long onSale = product(ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 1)).getId();
            top.changeActive(false);
            categoryRepository.save(top);
            detail(onSale).andExpect(status().isOk()).andExpect(jsonPath("$.data.purchasable").value(false));
        }

        @Test
        @DisplayName("삭제된 상품 · 없는 상품 — 404 PRODUCT_NOT_FOUND")
        void get_deletedOrMissing_notFound() throws Exception {
            long deleted = product(ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 1)).getId();
            jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE product_id = ?", deleted);

            detail(deleted).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
            detail(999999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        }
    }
}
