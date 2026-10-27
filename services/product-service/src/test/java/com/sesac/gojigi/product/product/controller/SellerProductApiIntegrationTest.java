package com.sesac.gojigi.product.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판매자 상품 등록 (POST /api/seller/products, product.md 13절) · 상세 (GET /api/seller/products/{id}, 12절) — 실제 MySQL · Flyway V1~V2.
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

    private ResultActions detail(long productId, String storeId) throws Exception {
        var req = get("/api/seller/products/" + productId).header("X-User-Id", "2").header("X-User-Roles", "SELLER");
        return mockMvc.perform(storeId == null ? req : req.header("X-Store-Id", storeId));
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
}
