package com.sesac.gojigi.product.option.controller;

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
import java.util.stream.Collectors;
import java.util.stream.LongStream;
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
 * 가격 · 구매 가능 조회 (GET /internal/product-options?ids=, product.md 4절) — 실제 MySQL · Flyway.
 * order 의 ProductClient 가 보내는 모양(ids 반복 · 쉼표) 둘 다 확인. 상품은 저장소로 직접 만든다 (InternalProductApiIntegrationTest 와 같음).
 * 트레이스 샘플링 0 — Zipkin 전송 실패 WARN 이 LogbackConfigTest 가짜 Loki 에 섞이지 않게.
 */
@SpringBootTest(properties = {"management.tracing.sampling.probability=0", "app.images.base-url=https://img.test/"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class InternalProductOptionApiIntegrationTest {

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
        jdbc.update("DELETE FROM product_image");
        jdbc.update("DELETE FROM product_option");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM category WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM category");
    }

    /** 89,000원 15% → 판매가 75,650원. 옵션 값이 있으면 그룹 2개("컬러" · "사이즈"), 없으면 옵션 없는 상품 */
    private Product product(long storeId, ProductStatus status, ProductOption... options) {
        boolean grouped = options[0].getOption1Value() != null;
        Product product = Product.builder()
                .storeId(storeId).categoryId(shirt.getId()).name("리넨 셔츠")
                .price(89_000L).discountRate(15)
                .material("리넨 100%").origin("대한민국")
                .option1Name(grouped ? "컬러" : null).option2Name(grouped ? "사이즈" : null)
                .status(status)
                .build();
        for (ProductOption option : options) {
            product.addOption(option);
        }
        product.addImage(ProductImage.of("products/" + storeId + "/main.jpg", 0));
        product.addImage(ProductImage.of("products/" + storeId + "/second.jpg", 1));
        return productRepository.save(product);
    }

    private long optionId(Product product, int index) {
        return product.getOptions().get(index).getId();
    }

    private ResultActions find(String query) throws Exception {
        return mockMvc.perform(get("/internal/product-options?" + query));
    }

    @Nested
    @DisplayName("조회")
    class Find {

        @Test
        @DisplayName("판매 중 옵션 — 13개 필드, 단가 = 판매가 + 추가금, 상위 카테고리 · 대표 이미지, 품절이어도 purchasable true")
        void findOptions_onSale_returnsSnapshotFields() throws Exception {
            // given
            Product p = product(1L, ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 30), ProductOption.of("오트밀", "L", 2000, 0));
            long m = optionId(p, 0);
            long l = optionId(p, 1);

            // when
            find("ids=" + m + "&ids=" + l)
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].productOptionId").value(m))
                    .andExpect(jsonPath("$.data[0].productId").value(p.getId()))
                    .andExpect(jsonPath("$.data[0].productName").value("리넨 셔츠"))
                    .andExpect(jsonPath("$.data[0].optionName").value("오트밀 / M"))
                    .andExpect(jsonPath("$.data[0].storeId").value(1))
                    .andExpect(jsonPath("$.data[0].categoryId").value(shirt.getId()))
                    .andExpect(jsonPath("$.data[0].topCategoryId").value(top.getId()))
                    .andExpect(jsonPath("$.data[0].salePrice").value(75650))
                    .andExpect(jsonPath("$.data[0].extraPrice").value(0))
                    .andExpect(jsonPath("$.data[0].unitPrice").value(75650))
                    .andExpect(jsonPath("$.data[0].stock").value(30))
                    .andExpect(jsonPath("$.data[0].purchasable").value(true))
                    .andExpect(jsonPath("$.data[0].imageUrl").value("https://img.test/products/1/main.jpg"))
                    .andExpect(jsonPath("$.data[1].unitPrice").value(77650))
                    .andExpect(jsonPath("$.data[1].stock").value(0))
                    .andExpect(jsonPath("$.data[1].purchasable").value(true));
        }

        @Test
        @DisplayName("요청 순서 · 중복은 한 번 · 없는 ID 는 빠짐 · 쉼표 형식도 같음 · 옵션 없는 상품은 optionName null")
        void findOptions_mixedIds_keepsRequestOrderAndSkipsMissing() throws Exception {
            // given
            long a = optionId(product(1L, ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 1)), 0);
            long b = optionId(product(2L, ProductStatus.ON_SALE, ProductOption.of(null, null, 0, 5)), 0);

            // when · then
            find("ids=" + b + "," + a + ",999999," + b)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].productOptionId").value(b))
                    .andExpect(jsonPath("$.data[0].optionName").doesNotExist())
                    .andExpect(jsonPath("$.data[0].storeId").value(2))
                    .andExpect(jsonPath("$.data[1].productOptionId").value(a));
            find("ids=999999").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    @Nested
    @DisplayName("구매 불가 (목록에는 포함, purchasable false)")
    class NotPurchasable {

        @Test
        @DisplayName("판매중지 · 강제 판매중지 · 상품 삭제 · 옵션 삭제")
        void findOptions_stoppedBlockedOrDeleted_notPurchasable() throws Exception {
            // given
            long stopped = optionId(product(1L, ProductStatus.STOPPED, ProductOption.of(null, null, 0, 5)), 0);
            Product blockedProduct = product(1L, ProductStatus.ON_SALE, ProductOption.of(null, null, 0, 5));
            blockedProduct.block("허위 표시");
            productRepository.save(blockedProduct);
            long blocked = optionId(blockedProduct, 0);
            Product deletedProduct = product(1L, ProductStatus.ON_SALE, ProductOption.of(null, null, 0, 5));
            jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE product_id = ?", deletedProduct.getId());
            long productDeleted = optionId(deletedProduct, 0);
            Product p = product(1L, ProductStatus.ON_SALE, ProductOption.of("오트밀", "M", 0, 5), ProductOption.of("오트밀", "L", 0, 5));
            long optionDeleted = optionId(p, 1);
            jdbc.update("UPDATE product_option SET deleted_at = NOW(6) WHERE product_option_id = ?", optionDeleted);

            // when · then
            find("ids=" + stopped + "," + blocked + "," + productDeleted + "," + optionDeleted + "," + optionId(p, 0))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(5))
                    .andExpect(jsonPath("$.data[0].purchasable").value(false))
                    .andExpect(jsonPath("$.data[1].purchasable").value(false))
                    .andExpect(jsonPath("$.data[2].purchasable").value(false))
                    .andExpect(jsonPath("$.data[3].purchasable").value(false))
                    .andExpect(jsonPath("$.data[4].purchasable").value(true)); // 같은 상품의 삭제 안 된 옵션은 그대로
        }

        @Test
        @DisplayName("하위 카테고리 꺼짐 · 상위 카테고리 꺼짐 · 스토어 비활성")
        void findOptions_categoryOrStoreInactive_notPurchasable() throws Exception {
            // given
            Product p = product(1L, ProductStatus.ON_SALE, ProductOption.of(null, null, 0, 5));
            long id = optionId(p, 0);

            // when · then — 하위 꺼짐
            shirt.changeActive(false);
            categoryRepository.save(shirt);
            find("ids=" + id).andExpect(jsonPath("$.data[0].purchasable").value(false));
            // 하위 다시 켜고 상위 꺼짐
            shirt.changeActive(true);
            categoryRepository.save(shirt);
            top.changeActive(false);
            categoryRepository.save(top);
            find("ids=" + id).andExpect(jsonPath("$.data[0].purchasable").value(false));
            // 카테고리 모두 켜고 스토어 비활성 (StoreStatusChanged 소비 전이라 DB 로 직접)
            top.changeActive(true);
            categoryRepository.save(top);
            find("ids=" + id).andExpect(jsonPath("$.data[0].purchasable").value(true));
            jdbc.update("UPDATE product SET is_store_active = FALSE WHERE product_id = ?", p.getId());
            find("ids=" + id).andExpect(jsonPath("$.data[0].purchasable").value(false));
        }
    }

    @Test
    @DisplayName("ids 없음 · 비었음 · 101개 · 숫자 아님 — 400 INVALID_INPUT, 100개는 통과")
    void findOptions_invalidIds_invalidInput() throws Exception {
        String hundred = LongStream.rangeClosed(1, 100).mapToObj(String::valueOf).collect(Collectors.joining(","));
        find("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        find("ids=").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        find("ids=" + hundred + ",101").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        find("ids=abc").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        find("ids=" + hundred).andExpect(status().isOk());
    }
}
