package com.sesac.gojigi.product.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.product.dto.request.ProductCreateRequest;
import com.sesac.gojigi.product.product.dto.request.ProductStatusChangeRequest;
import com.sesac.gojigi.product.product.dto.response.ProductStatusChangeResponse;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import com.sesac.gojigi.product.product.service.SellerProductService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판매자 상품 목록 (GET /api/seller/products, 11절) · 상세 (GET /api/seller/products/{id}, 12절) · 등록 (POST, 13절) · 수정 (PUT, 13절) · 판매중지/재개 (PATCH /status, 15절) · 삭제 (DELETE, 16절) — 실제 MySQL · Flyway V1~V2.
 * 게이트웨이가 넣는 X-User-* · X-Store-Id 헤더를 직접 붙인다. 상품 → 카테고리 순으로 비워 FK 에 걸리지 않게 한다.
 * 트레이스 샘플링 0 — Zipkin 전송 실패 WARN 이 LogbackConfigTest 가짜 Loki 에 섞이지 않게 (CategoryApiIntegrationTest 와 같음).
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SellerProductApiIntegrationTest {

    private static final long STORE_ID = 1L;

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired SellerProductService sellerProductService;

    private Category top;
    private Category shirts;

    @BeforeEach
    void setUp() {
        clean();
        top = Category.top("패션의류", "Apparel", 1);
        top.changeActive(true);
        top = categoryRepository.save(top);
        shirts = categoryRepository.save(Category.child(top.getId(), "셔츠", "Shirts", 1));
    }

    @AfterEach
    void clean() {
        productRepository.deleteAll(); // 옵션 · 이미지는 cascade 로 함께
        categoryRepository.deleteAll(categoryRepository.findAll().stream().filter(c -> !c.isTop()).toList());
        categoryRepository.deleteAll();
    }

    /** 옵션 그룹 2개 · 조합 2개 · 이미지 2장인 정상 요청. 테스트마다 한 곳만 바꿔 쓴다 */
    private Map<String, Object> validRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("categoryId", shirts.getId());
        body.put("name", " 프렌치 리넨 오버핏 셔츠 ");
        body.put("price", 89000);
        body.put("discountRate", 15);
        body.put("description", "<p>시원한 리넨</p>");
        body.put("material", "리넨 100%");
        body.put("origin", "대한민국");
        body.put("option1Name", "컬러");
        body.put("option2Name", "사이즈");
        body.put("options", List.of(
                option("오트밀", "M", 0, 30),
                option("오트밀", "L", 2000, 0)));
        body.put("imageKeys", List.of("products/1/7f3c9e2a.jpg", "products/1/c3d4-e5f6.png"));
        return body;
    }

    private static Map<String, Object> option(String value1, String value2, long extraPrice, int stock) {
        Map<String, Object> option = new HashMap<>();
        option.put("option1Value", value1);
        option.put("option2Value", value2);
        option.put("extraPrice", extraPrice);
        option.put("stock", stock);
        return option;
    }

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/seller/products")
                .header("X-User-Id", "2").header("X-User-Roles", "SELLER").header("X-Store-Id", String.valueOf(STORE_ID))
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)));
    }

    private long registerOk() throws Exception {
        String body = register(validRequest()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body).path("data").path("productId").asLong();
    }

    private ResultActions list(String storeId) throws Exception {
        var req = get("/api/seller/products").header("X-User-Id", "2").header("X-User-Roles", "SELLER");
        return mockMvc.perform(storeId == null ? req : req.header("X-Store-Id", storeId));
    }

    private ResultActions detail(long productId, String storeId) throws Exception {
        var req = get("/api/seller/products/" + productId).header("X-User-Id", "2").header("X-User-Roles", "SELLER");
        return mockMvc.perform(storeId == null ? req : req.header("X-Store-Id", storeId));
    }

    /** 상세 응답에서 옵션 ID 를 등록 순서대로 */
    private List<Long> optionIds(long productId) throws Exception {
        String body = detail(productId, String.valueOf(STORE_ID)).andReturn().getResponse().getContentAsString();
        List<Long> ids = new ArrayList<>();
        jsonMapper.readTree(body).path("data").path("options").forEach(o -> ids.add(o.path("productOptionId").asLong()));
        return ids;
    }

    private ResultActions update(long productId, String storeId, Map<String, Object> body) throws Exception {
        var req = put("/api/seller/products/" + productId).header("X-User-Id", "2").header("X-User-Roles", "SELLER")
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        return mockMvc.perform(storeId == null ? req : req.header("X-Store-Id", storeId));
    }

    private ResultActions changeStatus(String storeId, String json) throws Exception {
        var req = patch("/api/seller/products/status").header("X-User-Id", "2").header("X-User-Roles", "SELLER")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return mockMvc.perform(storeId == null ? req : req.header("X-Store-Id", storeId));
    }

    private String productStatus(long productId) {
        return jdbc.queryForObject("SELECT status FROM product WHERE product_id = ?", String.class, productId);
    }

    private ResultActions remove(long productId, String storeId) throws Exception {
        var req = delete("/api/seller/products/" + productId).header("X-User-Id", "2").header("X-User-Roles", "SELLER");
        return mockMvc.perform(storeId == null ? req : req.header("X-Store-Id", storeId));
    }

    private static Map<String, Object> keptOption(long id, String value1, String value2, long extraPrice, int stock) {
        Map<String, Object> option = option(value1, value2, extraPrice, stock);
        option.put("productOptionId", id);
        return option;
    }

    private ResultActions register(Consumer<Map<String, Object>> change) throws Exception {
        Map<String, Object> body = validRequest();
        change.accept(body);
        return register(body);
    }

    @Nested
    @DisplayName("정상 등록")
    class Success {

        @Test
        @DisplayName("옵션 그룹 2개 — 201, 판매가 서버 계산 · 이미지 URL · 옵션 재고 · 기본 ON_SALE, 옵션 · 이미지가 DB 에 순서대로 저장")
        void create_twoOptionGroups_returnsSellerDetail() throws Exception {
            // when
            register(validRequest())
                    // then
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.storeId").value(STORE_ID))
                    .andExpect(jsonPath("$.data.categoryName").value("셔츠"))
                    .andExpect(jsonPath("$.data.topCategoryId").value(top.getId()))
                    .andExpect(jsonPath("$.data.topCategoryName").value("패션의류"))
                    .andExpect(jsonPath("$.data.name").value("프렌치 리넨 오버핏 셔츠"))
                    .andExpect(jsonPath("$.data.salePrice").value(75650))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"discountRate\":15.00"))) // DB 에서 다시 읽은 상세와 같은 자릿수
                    .andExpect(jsonPath("$.data.images[0].key").value("products/1/7f3c9e2a.jpg"))
                    .andExpect(jsonPath("$.data.images[0].url")
                            .value("https://sesac-gojigi-images-4380.s3.ap-northeast-2.amazonaws.com/products/1/7f3c9e2a.jpg"))
                    .andExpect(jsonPath("$.data.options.length()").value(2))
                    .andExpect(jsonPath("$.data.options[0].productOptionId").isNumber())
                    .andExpect(jsonPath("$.data.options[1].extraPrice").value(2000))
                    .andExpect(jsonPath("$.data.options[1].stock").value(0))
                    .andExpect(jsonPath("$.data.options[1].soldOut").value(true))
                    .andExpect(jsonPath("$.data.soldOut").value(false))
                    .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                    .andExpect(jsonPath("$.data.hiddenReason").doesNotExist())
                    .andExpect(jsonPath("$.data.purchasable").value(true))
                    .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")));

            transactionTemplate.executeWithoutResult(tx -> {
                Product saved = productRepository.findAll().getFirst();
                assertThat(saved.getOptions()).extracting("option1Value", "option2Value", "stock")
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("오트밀", "M", 30),
                                org.assertj.core.groups.Tuple.tuple("오트밀", "L", 0));
                assertThat(saved.getImages()).extracting("s3Key", "sortOrder")
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("products/1/7f3c9e2a.jpg", 0),
                                org.assertj.core.groups.Tuple.tuple("products/1/c3d4-e5f6.png", 1));
            });
        }

        @Test
        @DisplayName("옵션 없는 상품 — 그룹 이름 null · 값이 null 인 조합 1개")
        void create_noOptionGroup_savesSingleNullOption() throws Exception {
            // when
            register(body -> {
                body.remove("option1Name");
                body.put("option2Name", " ");
                body.put("options", List.of(option(null, null, 0, 5)));
            })
                    // then
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.option1Name").doesNotExist())
                    .andExpect(jsonPath("$.data.option2Name").doesNotExist())
                    .andExpect(jsonPath("$.data.options.length()").value(1))
                    .andExpect(jsonPath("$.data.options[0].option1Value").doesNotExist());
        }

        @Test
        @DisplayName("STOPPED 로 등록 — 미노출 이유 STOPPED · 구매 불가, 상세 설명의 script · on* 속성은 지워서 저장")
        void create_stoppedWithScript_hiddenAndSanitized() throws Exception {
            // when
            register(body -> {
                body.put("status", "STOPPED");
                body.put("description", "<p onclick=\"alert(1)\">리넨</p><script>alert(1)</script>");
            })
                    // then
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("STOPPED"))
                    .andExpect(jsonPath("$.data.hiddenReason").value("STOPPED"))
                    .andExpect(jsonPath("$.data.purchasable").value(false))
                    .andExpect(jsonPath("$.data.description").value("<p>리넨</p>"));
        }
    }

    @Nested
    @DisplayName("거절")
    class Reject {

        @Test
        @DisplayName("X-Store-Id 없음 — 403 FORBIDDEN")
        void create_noStoreHeader_forbidden() throws Exception {
            // when
            mockMvc.perform(post("/api/seller/products")
                            .header("X-User-Id", "4").header("X-User-Roles", "BUYER")
                            .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(validRequest())))
                    // then
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            assertThat(productRepository.count()).isZero();
        }

        @Test
        @DisplayName("없는 · 상위 · 꺼진 하위 · 상위가 꺼진 카테고리 — 400 PRODUCT_CATEGORY_INVALID")
        void create_unusableCategory_categoryInvalid() throws Exception {
            // given
            Category offChild = Category.child(top.getId(), "팬츠", null, 2);
            offChild.changeActive(false);
            offChild = categoryRepository.save(offChild);
            Category offTop = categoryRepository.save(Category.top("뷰티", null, 2));
            Category childOfOffTop = categoryRepository.save(Category.child(offTop.getId(), "스킨케어", null, 1));

            // when · then
            for (long categoryId : List.of(999999L, top.getId(), offChild.getId(), childOfOffTop.getId())) {
                register(body -> body.put("categoryId", categoryId))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("PRODUCT_CATEGORY_INVALID"));
            }
            assertThat(productRepository.count()).isZero();
        }

        static Stream<Arguments> invalidOptions() {
            return Stream.of(
                    Arguments.of("조합 0개", (Consumer<Map<String, Object>>) b -> b.put("options", List.of())),
                    Arguments.of("조합 101개", (Consumer<Map<String, Object>>) b -> b.put("options",
                            Stream.iterate(0, i -> i + 1).limit(101).map(i -> option("c" + i, "M", 0, 1)).toList())),
                    Arguments.of("그룹 2 만 있음", (Consumer<Map<String, Object>>) b -> b.remove("option1Name")),
                    Arguments.of("두 그룹 이름이 같음", (Consumer<Map<String, Object>>) b -> b.put("option2Name", "컬러")),
                    Arguments.of("값 개수가 그룹 수와 다름", (Consumer<Map<String, Object>>) b -> b.put("options",
                            List.of(option("오트밀", null, 0, 1)))),
                    Arguments.of("조합 중복 (앞뒤 공백 무시)", (Consumer<Map<String, Object>>) b -> b.put("options",
                            List.of(option("오트밀", "M", 0, 1), option(" 오트밀 ", "M", 0, 1)))),
                    Arguments.of("추가금 음수", (Consumer<Map<String, Object>>) b -> b.put("options",
                            List.of(option("오트밀", "M", -1, 1)))),
                    Arguments.of("재고 음수", (Consumer<Map<String, Object>>) b -> b.put("options",
                            List.of(option("오트밀", "M", 0, -1)))),
                    Arguments.of("옵션 값 31자", (Consumer<Map<String, Object>>) b -> b.put("options",
                            List.of(option("가".repeat(31), "M", 0, 1)))),
                    Arguments.of("옵션 없는 상품인데 조합 2개", (Consumer<Map<String, Object>>) b -> {
                        b.remove("option1Name");
                        b.remove("option2Name");
                        b.put("options", List.of(option(null, null, 0, 1), option(null, null, 0, 2)));
                    }));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidOptions")
        @DisplayName("옵션 규칙 위반 — 400 PRODUCT_OPTION_INVALID")
        void create_invalidOptions_optionInvalid(String caseName, Consumer<Map<String, Object>> change) throws Exception {
            register(change)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRODUCT_OPTION_INVALID"));
        }

        static Stream<Arguments> invalidImageKeys() {
            return Stream.of(
                    Arguments.of("0장", List.of()),
                    Arguments.of("6장", Stream.iterate(0, i -> i + 1).limit(6).map(i -> "products/1/img" + i + ".jpg").toList()),
                    Arguments.of("다른 스토어 키", List.of("products/2/7f3c9e2a.jpg")),
                    Arguments.of("스토어 1로 시작하는 다른 번호", List.of("products/12/7f3c9e2a.jpg")),
                    Arguments.of("허용하지 않는 확장자", List.of("products/1/7f3c9e2a.gif")),
                    Arguments.of("경로 이동", List.of("products/1/../2/7f3c9e2a.jpg")),
                    Arguments.of("중복", List.of("products/1/7f3c9e2a.jpg", "products/1/7f3c9e2a.jpg")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidImageKeys")
        @DisplayName("이미지 키 규칙 위반 — 400 PRODUCT_IMAGE_INVALID")
        void create_invalidImageKeys_imageInvalid(String caseName, List<String> imageKeys) throws Exception {
            register(body -> body.put("imageKeys", imageKeys))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_INVALID"));
        }

        static Stream<Arguments> invalidInputs() {
            return Stream.of(
                    Arguments.of("BLOCKED 로 등록", (Consumer<Map<String, Object>>) b -> b.put("status", "BLOCKED")),
                    Arguments.of("없는 상태 값", (Consumer<Map<String, Object>>) b -> b.put("status", "SOLD_OUT")),
                    Arguments.of("가격 99원", (Consumer<Map<String, Object>>) b -> b.put("price", 99)),
                    Arguments.of("할인율 91", (Consumer<Map<String, Object>>) b -> b.put("discountRate", 91)),
                    Arguments.of("상품명 공백", (Consumer<Map<String, Object>>) b -> b.put("name", " ")),
                    Arguments.of("원산지 51자", (Consumer<Map<String, Object>>) b -> b.put("origin", "가".repeat(51))),
                    Arguments.of("카테고리 없음", (Consumer<Map<String, Object>>) b -> b.remove("categoryId")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidInputs")
        @DisplayName("형식 오류 — 400 INVALID_INPUT")
        void create_invalidInput_invalidInput(String caseName, Consumer<Map<String, Object>> change) throws Exception {
            register(change)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Nested
    @DisplayName("판매자 상품 상세")
    class Detail {

        @Test
        @DisplayName("내 스토어 상품 — 200, 등록한 내용 그대로 (옵션 등록 순서 · 이미지 순서 · 할인율 15.00 · 옵션별 재고)")
        void get_ownProduct_returnsDetail() throws Exception {
            // given
            long productId = registerOk();

            // when
            detail(productId, String.valueOf(STORE_ID))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productId").value(productId))
                    .andExpect(jsonPath("$.data.categoryName").value("셔츠"))
                    .andExpect(jsonPath("$.data.topCategoryName").value("패션의류"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"discountRate\":15.00")))
                    .andExpect(jsonPath("$.data.description").value("<p>시원한 리넨</p>"))
                    .andExpect(jsonPath("$.data.options[0].option2Value").value("M"))
                    .andExpect(jsonPath("$.data.options[0].stock").value(30))
                    .andExpect(jsonPath("$.data.options[1].option2Value").value("L"))
                    .andExpect(jsonPath("$.data.options[1].soldOut").value(true))
                    .andExpect(jsonPath("$.data.images[0].key").value("products/1/7f3c9e2a.jpg"))
                    .andExpect(jsonPath("$.data.images[1].url")
                            .value("https://sesac-gojigi-images-4380.s3.ap-northeast-2.amazonaws.com/products/1/c3d4-e5f6.png"))
                    .andExpect(jsonPath("$.data.hiddenReason").doesNotExist())
                    .andExpect(jsonPath("$.data.purchasable").value(true));
        }

        @Test
        @DisplayName("등록 뒤 상위 카테고리를 끔 — 200, 상태는 ON_SALE 그대로 · 미노출 이유 CATEGORY_INACTIVE · 구매 불가")
        void get_topCategoryTurnedOff_hiddenByCategory() throws Exception {
            // given
            long productId = registerOk();
            top.changeActive(false);
            categoryRepository.save(top);

            // when
            detail(productId, String.valueOf(STORE_ID))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                    .andExpect(jsonPath("$.data.hiddenReason").value("CATEGORY_INACTIVE"))
                    .andExpect(jsonPath("$.data.purchasable").value(false));
        }

        @Test
        @DisplayName("다른 스토어 상품 · 삭제된 상품 · 없는 상품 — 모두 404 PRODUCT_NOT_FOUND (구분하지 않음)")
        void get_notMine_notFound() throws Exception {
            // given
            long productId = registerOk();
            long deletedId = registerOk();
            transactionTemplate.executeWithoutResult(tx ->
                    productRepository.findById(deletedId).orElseThrow().delete(java.time.LocalDateTime.now()));

            // when · then
            detail(productId, "2").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
            detail(deletedId, String.valueOf(STORE_ID)).andExpect(status().isNotFound());
            detail(999999L, String.valueOf(STORE_ID)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("X-Store-Id 없음 — 403 FORBIDDEN")
        void get_noStoreHeader_forbidden() throws Exception {
            // given
            long productId = registerOk();

            // when · then
            detail(productId, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Nested
    @DisplayName("판매자 상품 목록")
    class SellerList {

        @Test
        @DisplayName("내 스토어 상품만 · 삭제 제외 · 최신 순 — 재고 합계 · 대표 이미지 URL · 품절 · 미노출 이유")
        void list_ownStore_returnsNewestFirst() throws Exception {
            // given — 1: 재고 30 + 0, 2: STOPPED · 옵션 없이 재고 0, 3: 삭제, 4: 다른 스토어
            long first = registerOk();
            String second = register(body -> {
                body.put("name", "판매중지 품절 상품");
                body.put("status", "STOPPED");
                body.remove("option1Name");
                body.remove("option2Name");
                body.put("options", List.of(option(null, null, 0, 0)));
                body.put("imageKeys", List.of("products/1/second.webp"));
            }).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long secondId = jsonMapper.readTree(second).path("data").path("productId").asLong();
            long deleted = registerOk();
            transactionTemplate.executeWithoutResult(tx ->
                    productRepository.findById(deleted).orElseThrow().delete(java.time.LocalDateTime.now()));
            mockMvc.perform(post("/api/seller/products")
                            .header("X-User-Id", "3").header("X-User-Roles", "SELLER").header("X-Store-Id", "2")
                            .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(
                                    Map.of("categoryId", shirts.getId(), "name", "다른 스토어", "price", 1000, "discountRate", 0,
                                            "material", "면", "origin", "대한민국",
                                            "options", List.of(option(null, null, 0, 1)), "imageKeys", List.of("products/2/x.jpg")))))
                    .andExpect(status().isCreated());

            // when
            list(String.valueOf(STORE_ID))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].productId").value(secondId))
                    .andExpect(jsonPath("$.data[0].totalStock").value(0))
                    .andExpect(jsonPath("$.data[0].soldOut").value(true))
                    .andExpect(jsonPath("$.data[0].status").value("STOPPED"))
                    .andExpect(jsonPath("$.data[0].displayable").value(false))
                    .andExpect(jsonPath("$.data[0].hiddenReason").value("STOPPED"))
                    .andExpect(jsonPath("$.data[0].imageUrl")
                            .value("https://sesac-gojigi-images-4380.s3.ap-northeast-2.amazonaws.com/products/1/second.webp"))
                    .andExpect(jsonPath("$.data[1].productId").value(first))
                    .andExpect(jsonPath("$.data[1].categoryName").value("셔츠"))
                    .andExpect(jsonPath("$.data[1].topCategoryName").value("패션의류"))
                    .andExpect(jsonPath("$.data[1].salePrice").value(75650))
                    .andExpect(jsonPath("$.data[1].totalStock").value(30))
                    .andExpect(jsonPath("$.data[1].soldOut").value(false))
                    .andExpect(jsonPath("$.data[1].displayable").value(true))
                    .andExpect(jsonPath("$.data[1].hiddenReason").doesNotExist())
                    .andExpect(jsonPath("$.data[1].imageUrl")
                            .value("https://sesac-gojigi-images-4380.s3.ap-northeast-2.amazonaws.com/products/1/7f3c9e2a.jpg"));
        }

        @Test
        @DisplayName("상품이 없는 스토어 — 200 빈 배열")
        void list_noProducts_returnsEmpty() throws Exception {
            list(String.valueOf(STORE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("X-Store-Id 없음 — 403 FORBIDDEN")
        void list_noStoreHeader_forbidden() throws Exception {
            list(null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Nested
    @DisplayName("상품 수정")
    class Update {

        @Test
        @DisplayName("기존 조합은 추가금만 · 새 조합 추가 · 빠진 조합 소프트 삭제, 판매가 재계산 · 이미지 다시 저장, 상태 · 기존 재고 그대로")
        void update_keepAddDrop_appliesOptionsAndKeepsStockStatus() throws Exception {
            // given — 오트밀 M(30) · L(0), STOPPED 로 등록
            Map<String, Object> created = validRequest();
            created.put("status", "STOPPED");
            String body = register(created).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long productId = jsonMapper.readTree(body).path("data").path("productId").asLong();
            List<Long> ids = optionIds(productId);
            long m = ids.get(0);
            long l = ids.get(1);

            Map<String, Object> request = validRequest();
            request.put("name", "리넨 셔츠 (수정)");
            request.put("price", 50000);
            request.put("discountRate", 10);
            request.put("status", "ON_SALE"); // 수정 때 무시
            request.put("options", List.of(
                    keptOption(m, "오트밀", "M", 1000, 999), // 추가금만 바뀌고 재고 999 는 무시
                    option("차콜", "M", 0, 7)));             // 새 조합 — 재고 7 로 시작
            request.put("imageKeys", List.of("products/1/c3d4-e5f6.png", "products/1/new-1.webp"));

            // when
            update(productId, String.valueOf(STORE_ID), request)
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("리넨 셔츠 (수정)"))
                    .andExpect(jsonPath("$.data.salePrice").value(45000))
                    .andExpect(jsonPath("$.data.status").value("STOPPED"))
                    .andExpect(jsonPath("$.data.options.length()").value(2))
                    .andExpect(jsonPath("$.data.options[0].productOptionId").value(m))
                    .andExpect(jsonPath("$.data.options[0].extraPrice").value(1000))
                    .andExpect(jsonPath("$.data.options[0].stock").value(30))
                    .andExpect(jsonPath("$.data.options[1].option1Value").value("차콜"))
                    .andExpect(jsonPath("$.data.options[1].productOptionId").isNumber())
                    .andExpect(jsonPath("$.data.options[1].stock").value(7))
                    .andExpect(jsonPath("$.data.images[0].key").value("products/1/c3d4-e5f6.png"))
                    .andExpect(jsonPath("$.data.images[1].key").value("products/1/new-1.webp"))
                    .andExpect(jsonPath("$.data.images.length()").value(2));
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM product_option WHERE product_option_id = ?", Boolean.class, l)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ?", Integer.class, productId)).isEqualTo(2);

            // 다시 읽어도 삭제된 조합(L)은 빠진다 (#115 때 확인 못 한 것)
            detail(productId, String.valueOf(STORE_ID))
                    .andExpect(jsonPath("$.data.options.length()").value(2))
                    .andExpect(jsonPath("$.data.options[?(@.productOptionId == " + l + ")]").isEmpty());
        }

        @Test
        @DisplayName("옵션 그룹을 바꾸면 기존 조합은 모두 빠지고 새 조합만 — 그룹 2개 → 옵션 없는 상품")
        void update_changeGroups_replacesAllOptions() throws Exception {
            long productId = registerOk();
            Map<String, Object> request = validRequest();
            request.remove("option1Name");
            request.remove("option2Name");
            request.put("options", List.of(option(null, null, 0, 5)));

            update(productId, String.valueOf(STORE_ID), request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.option1Name").doesNotExist())
                    .andExpect(jsonPath("$.data.options.length()").value(1))
                    .andExpect(jsonPath("$.data.options[0].stock").value(5));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_option WHERE product_id = ? AND deleted_at IS NOT NULL",
                    Integer.class, productId)).isEqualTo(2);
        }

        @Test
        @DisplayName("강제 판매중지 상품도 수정 가능 — 상태는 BLOCKED · 사유 그대로")
        void update_blockedProduct_staysBlocked() throws Exception {
            long productId = registerOk();
            jdbc.update("UPDATE product SET status = 'BLOCKED', status_reason = '허위 표시' WHERE product_id = ?", productId);
            List<Long> ids = optionIds(productId);
            Map<String, Object> request = validRequest();
            request.put("options", List.of(keptOption(ids.get(0), "오트밀", "M", 0, 30), keptOption(ids.get(1), "오트밀", "L", 2000, 0)));

            update(productId, String.valueOf(STORE_ID), request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                    .andExpect(jsonPath("$.data.statusReason").value("허위 표시"))
                    .andExpect(jsonPath("$.data.hiddenReason").value("BLOCKED"));
        }

        @Test
        @DisplayName("이 상품에 이미 붙은 키(시드 이미지 모양)는 그대로 받고, 남의 스토어 키는 400")
        void update_existingSeedKey_acceptedForeignKeyRejected() throws Exception {
            long productId = registerOk();
            jdbc.update("UPDATE product_image SET s3_key = 'products/seed/p601-1.jpg' WHERE product_id = ? AND sort_order = 0", productId);
            List<Long> ids = optionIds(productId);
            Map<String, Object> request = validRequest();
            request.put("options", List.of(keptOption(ids.get(0), "오트밀", "M", 0, 30), keptOption(ids.get(1), "오트밀", "L", 2000, 0)));
            request.put("imageKeys", List.of("products/seed/p601-1.jpg"));

            update(productId, String.valueOf(STORE_ID), request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.images[0].key").value("products/seed/p601-1.jpg"));

            request.put("imageKeys", List.of("products/2/other.jpg"));
            update(productId, String.valueOf(STORE_ID), request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_INVALID"));
        }

        @Test
        @DisplayName("기존 조합 값 변경 · 남의 상품 옵션 ID · 삭제된 옵션 ID — 400 PRODUCT_OPTION_INVALID, 아무것도 안 바뀜")
        void update_invalidOptionIds_rejected() throws Exception {
            // given
            long productId = registerOk();
            long otherProductId = registerOk();
            List<Long> ids = optionIds(productId);
            long otherOption = optionIds(otherProductId).getFirst();
            jdbc.update("UPDATE product_option SET deleted_at = NOW(6) WHERE product_option_id = ?", ids.get(1));

            // when · then
            for (Map<String, Object> bad : List.of(
                    keptOption(ids.get(0), "차콜", "M", 0, 1),     // 값 변경
                    keptOption(otherOption, "오트밀", "M", 0, 1),  // 다른 상품 옵션
                    keptOption(ids.get(1), "오트밀", "L", 0, 1))) { // 삭제된 옵션
                Map<String, Object> request = validRequest();
                request.put("name", "바뀌면 안 됨");
                request.put("options", List.of(bad));
                update(productId, String.valueOf(STORE_ID), request)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("PRODUCT_OPTION_INVALID"));
            }
            assertThat(jdbc.queryForObject("SELECT name FROM product WHERE product_id = ?", String.class, productId))
                    .isEqualTo("프렌치 리넨 오버핏 셔츠");
        }

        @Test
        @DisplayName("다른 스토어 상품 · 삭제된 상품 · 없는 상품 404, 스토어 헤더 없음 403")
        void update_notMineOrMissing_rejected() throws Exception {
            long productId = registerOk();
            long deletedId = registerOk();
            jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE product_id = ?", deletedId);

            update(productId, "2", validRequest()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
            update(deletedId, String.valueOf(STORE_ID), validRequest()).andExpect(status().isNotFound());
            update(999999L, String.valueOf(STORE_ID), validRequest()).andExpect(status().isNotFound());
            update(productId, null, validRequest()).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("수정 중에 주문이 재고를 빼고 관리자가 강제 판매중지해도 — 수정 저장이 재고 · 상태를 덮어쓰지 않음 (@DynamicUpdate)")
        void update_concurrentStockAndBlock_notOverwritten() throws Exception {
            // given — 오트밀 M 재고 30, ON_SALE
            long productId = registerOk();
            List<Long> ids = optionIds(productId);
            long m = ids.get(0);
            Map<String, Object> request = validRequest();
            request.put("name", "동시 수정");
            request.put("options", List.of(keptOption(m, "오트밀", "M", 500, 30), keptOption(ids.get(1), "오트밀", "L", 2000, 0)));
            ProductCreateRequest updateRequest = jsonMapper.convertValue(request, ProductCreateRequest.class);

            // 다른 트랜잭션이 재고 30 → 28 · 상태 BLOCKED 로 바꾸고 커밋하지 않은 채 1초 붙잡는다 (행 잠금)
            CountDownLatch locked = new CountDownLatch(1);
            Thread other = new Thread(() -> transactionTemplate.executeWithoutResult(tx -> {
                jdbc.update("UPDATE product_option SET stock = stock - 2 WHERE product_option_id = ?", m);
                jdbc.update("UPDATE product SET status = 'BLOCKED', status_reason = '동시 차단' WHERE product_id = ?", productId);
                locked.countDown();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            other.start();
            locked.await();

            // when — 수정은 커밋 전 값(재고 30 · ON_SALE)을 읽고, 저장은 잠금이 풀린 뒤에 된다
            sellerProductService.update(STORE_ID, productId, updateRequest);
            other.join();

            // then — 수정한 컬럼만 반영, 재고 · 상태는 다른 트랜잭션 값 그대로
            assertThat(jdbc.queryForObject("SELECT stock FROM product_option WHERE product_option_id = ?", Integer.class, m)).isEqualTo(28);
            assertThat(jdbc.queryForObject("SELECT extra_price FROM product_option WHERE product_option_id = ?", Long.class, m)).isEqualTo(500L);
            assertThat(jdbc.queryForObject("SELECT status FROM product WHERE product_id = ?", String.class, productId)).isEqualTo("BLOCKED");
            assertThat(jdbc.queryForObject("SELECT name FROM product WHERE product_id = ?", String.class, productId)).isEqualTo("동시 수정");
        }
    }

    @Nested
    @DisplayName("상품 삭제")
    class Delete {

        @Test
        @DisplayName("내 상품 — 200, 상품만 소프트 삭제(옵션 · 이미지 행은 남음) → 목록에서 빠지고 상세 · 재삭제 404, 옵션은 구매 불가 · 예약 409")
        void delete_ownProduct_softDeletesAndHides() throws Exception {
            // given
            long productId = registerOk();
            long kept = registerOk();
            List<Long> optionIds = optionIds(productId);

            // when
            remove(productId, String.valueOf(STORE_ID))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data").doesNotExist());
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM product WHERE product_id = ?", Boolean.class, productId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_option WHERE product_id = ? AND deleted_at IS NULL",
                    Integer.class, productId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ?", Integer.class, productId)).isEqualTo(2);

            list(String.valueOf(STORE_ID))
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].productId").value(kept));
            detail(productId, String.valueOf(STORE_ID)).andExpect(status().isNotFound());
            remove(productId, String.valueOf(STORE_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

            // order 가 부르는 내부 API — 장바구니에 담겨 있던 옵션
            mockMvc.perform(get("/internal/product-options?ids=" + optionIds.get(0)))
                    .andExpect(jsonPath("$.data[0].purchasable").value(false));
            mockMvc.perform(post("/internal/stock/reservations").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sagaId\":\"saga-del\",\"items\":[{\"productOptionId\":" + optionIds.get(0) + ",\"quantity\":1}]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODUCT_NOT_PURCHASABLE"));
        }

        @Test
        @DisplayName("강제 판매중지 · 판매중지 상품도 삭제 가능")
        void delete_blockedOrStopped_allowed() throws Exception {
            long blocked = registerOk();
            jdbc.update("UPDATE product SET status = 'BLOCKED', status_reason = '허위 표시' WHERE product_id = ?", blocked);
            String body = register(b -> b.put("status", "STOPPED")).andReturn().getResponse().getContentAsString();
            long stopped = jsonMapper.readTree(body).path("data").path("productId").asLong();

            remove(blocked, String.valueOf(STORE_ID)).andExpect(status().isOk());
            remove(stopped, String.valueOf(STORE_ID)).andExpect(status().isOk());
            list(String.valueOf(STORE_ID)).andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("다른 스토어 상품 · 없는 상품 404 (삭제 안 됨), 스토어 헤더 없음 403")
        void delete_notMineOrMissing_rejected() throws Exception {
            long productId = registerOk();

            remove(productId, "2").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
            remove(999999L, String.valueOf(STORE_ID)).andExpect(status().isNotFound());
            remove(productId, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM product WHERE product_id = ?", Boolean.class, productId)).isTrue();
        }
    }

    @Nested
    @DisplayName("판매중지 · 재개 (일괄)")
    class ChangeStatus {

        @Test
        @DisplayName("섞인 요청 — 판매중 · 이미 판매중지는 success, 강제 판매중지 · 남의 · 없는 · 삭제된 상품은 failed, 같은 ID 는 한 번")
        void changeStatus_mixed_splitsSuccessAndFailed() throws Exception {
            // given
            long onSale = registerOk();
            String stoppedBody = register(b -> b.put("status", "STOPPED")).andReturn().getResponse().getContentAsString();
            long stopped = jsonMapper.readTree(stoppedBody).path("data").path("productId").asLong();
            long blocked = registerOk();
            jdbc.update("UPDATE product SET status = 'BLOCKED', status_reason = '허위 표시' WHERE product_id = ?", blocked);
            long deleted = registerOk();
            jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE product_id = ?", deleted);
            long others = registerOk();
            jdbc.update("UPDATE product SET store_id = 2 WHERE product_id = ?", others);

            // when
            changeStatus(String.valueOf(STORE_ID), "{\"productIds\":[" + onSale + "," + stopped + "," + blocked + "," + others + ",999999,"
                    + deleted + "," + onSale + "],\"status\":\"STOPPED\"}")
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success.length()").value(2))
                    .andExpect(jsonPath("$.data.success[0]").value(onSale))
                    .andExpect(jsonPath("$.data.success[1]").value(stopped))
                    .andExpect(jsonPath("$.data.failed.length()").value(4))
                    .andExpect(jsonPath("$.data.failed[0].productId").value(blocked))
                    .andExpect(jsonPath("$.data.failed[0].code").value("PRODUCT_BLOCKED"))
                    .andExpect(jsonPath("$.data.failed[0].message").value("관리자가 판매중지한 상품은 판매 상태를 바꿀 수 없습니다."))
                    .andExpect(jsonPath("$.data.failed[1].productId").value(others))
                    .andExpect(jsonPath("$.data.failed[1].code").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.data.failed[2].productId").value(999999))
                    .andExpect(jsonPath("$.data.failed[3].productId").value(deleted));
            assertThat(productStatus(onSale)).isEqualTo("STOPPED");
            assertThat(productStatus(stopped)).isEqualTo("STOPPED");
            assertThat(productStatus(blocked)).isEqualTo("BLOCKED");
            assertThat(productStatus(others)).isEqualTo("ON_SALE");

            // 판매중지된 상품은 목록에서 미노출 이유 STOPPED
            list(String.valueOf(STORE_ID))
                    .andExpect(jsonPath("$.data[?(@.productId == " + onSale + ")].hiddenReason").value("STOPPED"));
        }

        @Test
        @DisplayName("재개 — STOPPED → ON_SALE, 재고 0 인 상품도 재개(품절로 보임)")
        void changeStatus_resume_allowedEvenSoldOut() throws Exception {
            // given
            String body = register(b -> {
                b.put("status", "STOPPED");
                b.put("options", List.of(option("오트밀", "M", 0, 0)));
            }).andReturn().getResponse().getContentAsString();
            long soldOut = jsonMapper.readTree(body).path("data").path("productId").asLong();

            // when · then
            changeStatus(String.valueOf(STORE_ID), "{\"productIds\":[" + soldOut + "],\"status\":\"ON_SALE\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success[0]").value(soldOut))
                    .andExpect(jsonPath("$.data.failed.length()").value(0));
            detail(soldOut, String.valueOf(STORE_ID))
                    .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                    .andExpect(jsonPath("$.data.soldOut").value(true));
        }

        @Test
        @DisplayName("BLOCKED 로 바꾸기 · 없는 상태 · status 없음 · productIds 비었음 · 101개 — 400, 아무것도 안 바뀜 / 헤더 없음 403")
        void changeStatus_invalidInput_rejectsAll() throws Exception {
            long id = registerOk();
            String hundredOne = LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));
            for (String json : List.of(
                    "{\"productIds\":[" + id + "],\"status\":\"BLOCKED\"}",
                    "{\"productIds\":[" + id + "],\"status\":\"SOLD_OUT\"}",
                    "{\"productIds\":[" + id + "]}",
                    "{\"productIds\":[],\"status\":\"STOPPED\"}",
                    "{\"productIds\":[" + hundredOne + "],\"status\":\"STOPPED\"}")) {
                changeStatus(String.valueOf(STORE_ID), json)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            }
            changeStatus(null, "{\"productIds\":[" + id + "],\"status\":\"STOPPED\"}").andExpect(status().isForbidden());
            assertThat(productStatus(id)).isEqualTo("ON_SALE");
        }

        @Test
        @DisplayName("재개하는 사이 관리자가 강제 판매중지 — 잠금 뒤 BLOCKED 를 보고 failed, 차단이 풀리지 않음")
        void changeStatus_concurrentAdminBlock_staysBlocked() throws Exception {
            // given — 판매중지 상태
            String body = register(b -> b.put("status", "STOPPED")).andReturn().getResponse().getContentAsString();
            long productId = jsonMapper.readTree(body).path("data").path("productId").asLong();

            // 관리자 트랜잭션이 BLOCKED 로 바꾸고 커밋하지 않은 채 1초 붙잡는다 (행 잠금)
            CountDownLatch locked = new CountDownLatch(1);
            Thread admin = new Thread(() -> transactionTemplate.executeWithoutResult(tx -> {
                jdbc.update("UPDATE product SET status = 'BLOCKED', status_reason = '동시 차단' WHERE product_id = ?", productId);
                locked.countDown();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            admin.start();
            locked.await();

            // when — 판매자 재개: 잠금을 기다렸다가 관리자 커밋 뒤의 상태를 본다
            ProductStatusChangeResponse result = sellerProductService.changeStatus(STORE_ID,
                    new ProductStatusChangeRequest(List.of(productId), ProductStatus.ON_SALE));
            admin.join();

            // then
            assertThat(result.success()).isEmpty();
            assertThat(result.failed()).extracting(ProductStatusChangeResponse.Failure::code).containsExactly("PRODUCT_BLOCKED");
            assertThat(productStatus(productId)).isEqualTo("BLOCKED");
        }
    }
}
