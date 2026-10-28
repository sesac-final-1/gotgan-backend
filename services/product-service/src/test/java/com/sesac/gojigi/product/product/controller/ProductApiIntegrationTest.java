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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 공개 상품 조회 — 목록 · 검색 (GET /api/products, 9절) · 상세 (GET /api/products/{id}, 10절) — 실제 MySQL · Flyway, 헤더 없이(로그인 없음) 부른다.
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

    /** 목록용 상품 — 이름 · 스토어 · 카테고리 · 정가(할인 없음 → 판매가 = 정가) · 상태 · 옵션 재고를 정해 저장 */
    private Product listed(String name, long storeId, Category category, long price, ProductStatus status, int... stocks) {
        Product product = Product.builder()
                .storeId(storeId).categoryId(category.getId()).name(name)
                .price(price).discountRate(0).material("면").origin("대한민국")
                .option1Name(stocks.length > 1 ? "사이즈" : null).status(status)
                .build();
        for (int i = 0; i < stocks.length; i++) {
            product.addOption(ProductOption.of(stocks.length > 1 ? "S" + i : null, null, 0, stocks[i]));
        }
        product.addImage(ProductImage.of("products/" + storeId + "/" + name.hashCode() + ".jpg", 0));
        return productRepository.save(product);
    }

    /** "a=1&q=%25" 모양을 받아 값마다 디코딩해 .param 으로 — get("…?q=%EB…") 는 % 를 다시 인코딩해 서버에 글자 그대로 감 */
    private ResultActions search(String query) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/products");
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                req.param(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return mockMvc.perform(req);
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

    @Nested
    @DisplayName("상품 목록 · 검색")
    class Search {

        private Category foodTop;
        private Category fruit;
        private Product shirtA;   // 스토어 1 · 셔츠 · 30,000 · 재고 있음
        private Product shirtB;   // 스토어 2 · 셔츠 · 10,000 · 품절
        private Product mandarin; // 스토어 1 · 과일 · 20,000 · 재고 있음 (옵션 2개 중 하나만 재고)

        @BeforeEach
        void products() {
            foodTop = Category.top("식품", "Food", 2);
            foodTop.changeActive(true);
            foodTop = categoryRepository.save(foodTop);
            fruit = categoryRepository.save(Category.child(foodTop.getId(), "과일", null, 1));
            shirtA = listed("리넨 셔츠", 1L, shirt, 30_000, ProductStatus.ON_SALE, 5);
            shirtB = listed("옥스퍼드 셔츠", 2L, shirt, 10_000, ProductStatus.ON_SALE, 0);
            mandarin = listed("제주 감귤 5kg", 1L, fruit, 20_000, ProductStatus.ON_SALE, 0, 3);
        }

        @Test
        @DisplayName("기본 — 쇼핑몰에 보이는 상품만, 신상품 순 · 카드 필드(대표 이미지 · 품절 · 상위 카테고리) · 페이지 정보")
        void search_default_onlyDisplayableNewestFirst() throws Exception {
            // given — 보이면 안 되는 상품들
            listed("판매중지", 1L, shirt, 1_000, ProductStatus.STOPPED, 1);
            long blocked = listed("강제중지", 1L, shirt, 1_000, ProductStatus.ON_SALE, 1).getId();
            jdbc.update("UPDATE product SET status = 'BLOCKED' WHERE product_id = ?", blocked);
            long deleted = listed("삭제됨", 1L, shirt, 1_000, ProductStatus.ON_SALE, 1).getId();
            jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE product_id = ?", deleted);
            long storeOff = listed("정지 스토어", 3L, shirt, 1_000, ProductStatus.ON_SALE, 1).getId();
            jdbc.update("UPDATE product SET is_store_active = FALSE WHERE product_id = ?", storeOff);
            Category offChild = Category.child(top.getId(), "꺼진 하위", null, 9);
            offChild.changeActive(false);
            listed("꺼진 카테고리", 1L, categoryRepository.save(offChild), 1_000, ProductStatus.ON_SALE, 1);

            // when
            search("")
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.content[0].productId").value(mandarin.getId()))
                    .andExpect(jsonPath("$.data.content[0].topCategoryId").value(foodTop.getId()))
                    .andExpect(jsonPath("$.data.content[0].soldOut").value(false))
                    .andExpect(jsonPath("$.data.content[0].imageUrl").value(org.hamcrest.Matchers.startsWith("https://img.test/products/1/")))
                    .andExpect(jsonPath("$.data.content[1].productId").value(shirtB.getId()))
                    .andExpect(jsonPath("$.data.content[1].soldOut").value(true))
                    .andExpect(jsonPath("$.data.content[2].productId").value(shirtA.getId()))
                    .andExpect(jsonPath("$.data.content[2].createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")));

            // 상위 카테고리를 끄면 그 아래 상품도 빠짐
            foodTop.changeActive(false);
            categoryRepository.save(foodTop);
            search("").andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("카테고리 — 상위 ID 면 아래 하위 전부, 하위 ID 면 그 하위만, 없는 카테고리는 빈 페이지 / 스토어 홈")
        void search_categoryAndStore_filters() throws Exception {
            search("categoryId=" + top.getId()).andExpect(jsonPath("$.data.totalElements").value(2));
            search("categoryId=" + shirt.getId()).andExpect(jsonPath("$.data.totalElements").value(2));
            search("categoryId=" + foodTop.getId())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].productId").value(mandarin.getId()));
            search("categoryId=999999").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
            search("storeId=1").andExpect(jsonPath("$.data.totalElements").value(2));
            search("storeId=2&categoryId=" + shirt.getId())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].productId").value(shirtB.getId()));
        }

        @Test
        @DisplayName("검색어 — 상품명 · 하위 카테고리 이름 포함, 앞뒤 공백 제거, 비면 조건 없음, % · _ 는 글자 그대로")
        void search_keyword_matchesNameOrCategory() throws Exception {
            listed("100% 순면 티", 1L, shirt, 5_000, ProductStatus.ON_SALE, 1);

            search("q=%EB%A6%AC%EB%84%A8").andExpect(jsonPath("$.data.totalElements").value(1));             // "리넨" — 상품명
            search("q=%EA%B3%BC%EC%9D%BC").andExpect(jsonPath("$.data.totalElements").value(1))              // "과일" — 하위 카테고리 이름
                    .andExpect(jsonPath("$.data.content[0].productId").value(mandarin.getId()));
            search("q=%20%20%EC%85%94%EC%B8%A0%20").andExpect(jsonPath("$.data.totalElements").value(3));   // "  셔츠 " — 이름 2 + 카테고리 이름 셔츠 1
            search("q=%20%20").andExpect(jsonPath("$.data.totalElements").value(4));                          // 공백뿐 — 조건 없음
            search("q=%25").andExpect(jsonPath("$.data.totalElements").value(1));                             // "%" — 모두가 아니라 글자 % 만
            search("q=_").andExpect(jsonPath("$.data.totalElements").value(0));                               // "_" — 아무 한 글자가 아님
        }

        @Test
        @DisplayName("가격대 — minPrice ≤ 판매가 < maxPrice, 품절 제외")
        void search_priceRangeAndSoldOut_filters() throws Exception {
            search("minPrice=10000&maxPrice=20000")
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].productId").value(shirtB.getId()));   // 20,000 은 미포함
            search("minPrice=20000").andExpect(jsonPath("$.data.totalElements").value(2));
            search("maxPrice=20000").andExpect(jsonPath("$.data.totalElements").value(1));
            search("minPrice=20000&maxPrice=20000").andExpect(jsonPath("$.data.totalElements").value(0));
            search("excludeSoldOut=true")
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content[?(@.productId == " + shirtB.getId() + ")]").isEmpty());
        }

        @Test
        @DisplayName("정렬 · 페이지 — 가격 오름 · 내림, 같은 가격은 productId 큰 순, 페이지를 넘겨도 순서 유지")
        void search_sortAndPaging_stableOrder() throws Exception {
            Product sameA = listed("같은 가격 A", 1L, shirt, 10_000, ProductStatus.ON_SALE, 1);
            Product sameB = listed("같은 가격 B", 1L, shirt, 10_000, ProductStatus.ON_SALE, 1);

            search("sort=PRICE_ASC")
                    .andExpect(jsonPath("$.data.content[0].productId").value(sameB.getId()))   // 10,000 셋 중 ID 큰 순
                    .andExpect(jsonPath("$.data.content[1].productId").value(sameA.getId()))
                    .andExpect(jsonPath("$.data.content[2].productId").value(shirtB.getId()))
                    .andExpect(jsonPath("$.data.content[4].productId").value(shirtA.getId()));
            search("sort=PRICE_DESC")
                    .andExpect(jsonPath("$.data.content[0].productId").value(shirtA.getId()))
                    .andExpect(jsonPath("$.data.content[1].productId").value(mandarin.getId()));
            search("sort=PRICE_ASC&size=2&page=1")
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.content[0].productId").value(shirtB.getId()))
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(3))
                    .andExpect(jsonPath("$.data.hasNext").value(true));
            search("size=2&page=5").andExpect(jsonPath("$.data.content.length()").value(0)).andExpect(jsonPath("$.data.totalElements").value(5));
        }

        @Test
        @DisplayName("min > max · 음수 · 모르는 정렬 · 숫자 아닌 ID · size 101 · size 0 · page 음수 · 검색어 51자 — 400 INVALID_INPUT (자바 내부 문구는 안 나감)")
        void search_invalidParams_badRequest() throws Exception {
            for (String query : List.of("minPrice=20000&maxPrice=10000", "minPrice=-1", "sort=POPULAR", "categoryId=abc", "size=101", "size=0",
                    "page=-1", "q=" + "a".repeat(51))) {
                search(query).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Failed to convert"))));
            }
            search("q=" + "a".repeat(50)).andExpect(status().isOk());
        }
    }
}
