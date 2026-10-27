package com.sesac.gojigi.product.product.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자 상품 조회 · 강제 판매중지 (/internal/products) · 상품 통계 (/internal/stats/products) — 실제 MySQL · Flyway V1~V2.
 * 테스트마다 상품 · 카테고리 표를 비우고 필요한 것만 만든다. 트레이스 샘플링 0 — CategoryApiIntegrationTest 와 같은 이유
 */
@SpringBootTest(properties = {"management.tracing.sampling.probability=0", "app.images.base-url=https://img.test/"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class InternalProductApiIntegrationTest {

    private static final long STORE = 1L;
    private static final long OTHER_STORE = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired JdbcTemplate jdbc;

    private Category top;
    private Category shirt;

    @BeforeEach
    void setUp() {
        clean();
        top = categoryRepository.save(Category.top("패션의류", "Apparel", 1));
        top.changeActive(true);
        top = categoryRepository.save(top);
        shirt = categoryRepository.save(Category.child(top.getId(), "셔츠", null, 1));
    }

    /** 끝나고도 지운다 — 같은 DB 를 쓰는 CategoryApiIntegrationTest 가 카테고리를 지울 때 상품 FK 에 걸리지 않게 */
    @AfterEach
    void clean() {
        // FK 순서대로 — 이미지 · 옵션 → 상품 → 하위 → 상위 카테고리
        jdbc.update("DELETE FROM product_image");
        jdbc.update("DELETE FROM product_option");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM category WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM category");
    }

    /** 옵션마다 재고를 받아 상품 1개 저장 (첫 이미지가 대표) */
    private Product product(long storeId, String name, ProductStatus status, int... stocks) {
        Product product = Product.builder()
                .storeId(storeId).categoryId(shirt.getId()).name(name)
                .price(89_000L).discountRate(15)
                .description("<p>설명</p>").material("리넨 100%").origin("대한민국")
                .option1Name(stocks.length > 1 ? "사이즈" : null)
                .status(status)
                .build();
        for (int i = 0; i < stocks.length; i++) {
            product.addOption(ProductOption.of(stocks.length > 1 ? "S" + i : null, null, 0, stocks[i]));
        }
        product.addImage(ProductImage.of("products/" + storeId + "/" + name + "-main.jpg", 0));
        product.addImage(ProductImage.of("products/" + storeId + "/" + name + "-2.jpg", 1));
        return productRepository.save(product);
    }

    private ResultActions block(long productId, String json) throws Exception {
        return mockMvc.perform(patch("/internal/products/" + productId + "/block").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Nested
    @DisplayName("스토어 상품 목록")
    class ListByStore {

        @Test
        @DisplayName("삭제된 상품 · 다른 스토어는 빼고 최신 순 — 재고 합계 · 대표 이미지 URL · 카테고리 이름 · 노출 여부")
        void listsStoreProducts() throws Exception {
            product(STORE, "셔츠A", ProductStatus.ON_SALE, 3, 4);
            Product stopped = product(STORE, "셔츠B", ProductStatus.STOPPED, 0);
            Product deleted = product(STORE, "셔츠C", ProductStatus.ON_SALE, 5);
            deleted.delete(LocalDateTime.now());
            productRepository.save(deleted);
            product(OTHER_STORE, "다른가게", ProductStatus.ON_SALE, 9);

            mockMvc.perform(get("/internal/products").param("storeId", String.valueOf(STORE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].productId").value(stopped.getId()))
                    .andExpect(jsonPath("$.data[0].soldOut").value(true))
                    .andExpect(jsonPath("$.data[0].displayable").value(false))
                    .andExpect(jsonPath("$.data[0].hiddenReason").value("STOPPED"))
                    .andExpect(jsonPath("$.data[1].name").value("셔츠A"))
                    .andExpect(jsonPath("$.data[1].totalStock").value(7))
                    .andExpect(jsonPath("$.data[1].salePrice").value(75_650))
                    .andExpect(jsonPath("$.data[1].imageUrl").value("https://img.test/products/1/셔츠A-main.jpg"))
                    .andExpect(jsonPath("$.data[1].categoryName").value("셔츠"))
                    .andExpect(jsonPath("$.data[1].topCategoryName").value("패션의류"))
                    .andExpect(jsonPath("$.data[1].displayable").value(true))
                    .andExpect(jsonPath("$.data[1].hiddenReason").doesNotExist());
        }

        @Test
        @DisplayName("판매중이어도 상위 카테고리가 꺼져 있으면 CATEGORY_INACTIVE")
        void categoryInactive() throws Exception {
            product(STORE, "셔츠A", ProductStatus.ON_SALE, 3);
            top.changeActive(false);
            categoryRepository.save(top);

            mockMvc.perform(get("/internal/products").param("storeId", String.valueOf(STORE)))
                    .andExpect(jsonPath("$.data[0].displayable").value(false))
                    .andExpect(jsonPath("$.data[0].hiddenReason").value("CATEGORY_INACTIVE"));
        }

        @Test
        @DisplayName("storeId 없으면 400 INVALID_INPUT, 상품 없는 스토어는 빈 배열")
        void storeIdRequired() throws Exception {
            mockMvc.perform(get("/internal/products"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            mockMvc.perform(get("/internal/products").param("storeId", "99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    @Nested
    @DisplayName("강제 판매중지 · 해제")
    class Block {

        @Test
        @DisplayName("차단하면 BLOCKED + 사유, 다시 차단하면 사유만 바뀌고, 해제하면 STOPPED · 사유 지움")
        void blockThenUnblock() throws Exception {
            long id = product(STORE, "셔츠A", ProductStatus.ON_SALE, 3).getId();

            block(id, "{\"blocked\":true,\"reason\":\" 원산지 표기 누락 \"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                    .andExpect(jsonPath("$.data.statusReason").value("원산지 표기 누락"));
            block(id, "{\"blocked\":true,\"reason\":\"상품 정보 고시 누락\"}")
                    .andExpect(jsonPath("$.data.statusReason").value("상품 정보 고시 누락"));
            block(id, "{\"blocked\":false}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("STOPPED"))
                    .andExpect(jsonPath("$.data.statusReason").doesNotExist());
        }

        @Test
        @DisplayName("BLOCKED 가 아닌 상품을 해제하면 그대로 200")
        void unblockNotBlocked() throws Exception {
            long id = product(STORE, "셔츠A", ProductStatus.ON_SALE, 3).getId();

            block(id, "{\"blocked\":false}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ON_SALE"));
        }

        @Test
        @DisplayName("사유 없는 차단 400 PRODUCT_BLOCK_REASON_REQUIRED, 없거나 삭제된 상품 404 PRODUCT_NOT_FOUND")
        void errors() throws Exception {
            Product product = product(STORE, "셔츠A", ProductStatus.ON_SALE, 3);
            block(product.getId(), "{\"blocked\":true,\"reason\":\"  \"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRODUCT_BLOCK_REASON_REQUIRED"));

            product.delete(LocalDateTime.now());
            productRepository.save(product);
            block(product.getId(), "{\"blocked\":true,\"reason\":\"사유\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
            block(999_999L, "{\"blocked\":false}")
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("상품 통계")
    class Stats {

        @BeforeEach
        void products() {
            product(STORE, "넉넉", ProductStatus.ON_SALE, 20);        // 판매중 (부족 아님 — 경계 20)
            product(STORE, "부족", ProductStatus.ON_SALE, 10, 9);     // 판매중 · 재고 부족 19
            product(STORE, "아주부족", ProductStatus.ON_SALE, 1);     // 판매중 · 재고 부족 1
            product(STORE, "품절", ProductStatus.ON_SALE, 0, 0);      // 품절
            product(STORE, "중지", ProductStatus.STOPPED, 5);         // 중지
            Product blocked = product(OTHER_STORE, "차단", ProductStatus.ON_SALE, 5);
            blocked.block("사유");
            productRepository.save(blocked);
        }

        @Test
        @DisplayName("storeId 있으면 store — 판매중 3 · 재고 부족 2(적은 순) · 품절 1 · 중지 1, platform 은 null")
        void storeStats() throws Exception {
            mockMvc.perform(get("/internal/stats/products").param("storeId", String.valueOf(STORE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.platform").doesNotExist())
                    .andExpect(jsonPath("$.data.store.onSaleCount").value(3))
                    .andExpect(jsonPath("$.data.store.lowStockCount").value(2))
                    .andExpect(jsonPath("$.data.store.soldOutCount").value(1))
                    .andExpect(jsonPath("$.data.store.stoppedCount").value(1))
                    .andExpect(jsonPath("$.data.store.lowStockProducts.length()").value(2))
                    .andExpect(jsonPath("$.data.store.lowStockProducts[0].name").value("아주부족"))
                    .andExpect(jsonPath("$.data.store.lowStockProducts[0].stock").value(1))
                    .andExpect(jsonPath("$.data.store.lowStockProducts[0].imageUrl").value("https://img.test/products/1/아주부족-main.jpg"))
                    .andExpect(jsonPath("$.data.store.lowStockProducts[1].stock").value(19));
        }

        @Test
        @DisplayName("storeId 없으면 platform — 전체 스토어 기준, 차단 1")
        void platformStats() throws Exception {
            mockMvc.perform(get("/internal/stats/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.store").doesNotExist())
                    .andExpect(jsonPath("$.data.platform.onSaleCount").value(3))
                    .andExpect(jsonPath("$.data.platform.lowStockCount").value(2))
                    .andExpect(jsonPath("$.data.platform.soldOutCount").value(1))
                    .andExpect(jsonPath("$.data.platform.blockedCount").value(1));
        }

        @Test
        @DisplayName("상품 없는 스토어는 모두 0 · 빈 목록")
        void emptyStore() throws Exception {
            mockMvc.perform(get("/internal/stats/products").param("storeId", "99"))
                    .andExpect(jsonPath("$.data.store.onSaleCount").value(0))
                    .andExpect(jsonPath("$.data.store.stoppedCount").value(0))
                    .andExpect(jsonPath("$.data.store.lowStockProducts.length()").value(0));
        }
    }
}
