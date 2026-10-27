package com.sesac.gojigi.product.stock.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.entity.ProductImage;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import com.sesac.gojigi.product.stock.dto.request.StockItem;
import com.sesac.gojigi.product.stock.dto.request.StockReserveRequest;
import com.sesac.gojigi.product.stock.service.StockReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 재고 예약 (POST /internal/stock/reservations, product.md 1절) — 실제 MySQL(InnoDB 잠금) · Flyway.
 * 상품은 저장소로 직접 만들고, 재고 · 원장은 JdbcTemplate 으로 읽어 엔티티 캐시와 상관없이 DB 값을 본다.
 * 트레이스 샘플링 0 — Zipkin 전송 실패 WARN 이 LogbackConfigTest 가짜 Loki 에 섞이지 않게.
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class InternalStockReservationApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired StockReservationService stockReservationService;
    @Autowired JdbcTemplate jdbc;

    private Category shirt;

    @BeforeEach
    void setUp() {
        clean();
        Category top = Category.top("패션의류", "Apparel", 1);
        top.changeActive(true);
        top = categoryRepository.save(top);
        shirt = categoryRepository.save(Category.child(top.getId(), "셔츠", null, 1));
    }

    /** 끝나고도 지운다 — 같은 DB 를 쓰는 다른 테스트가 카테고리를 지울 때 FK 에 걸리지 않게 (원장 → 이미지 · 옵션 → 상품 → 카테고리) */
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM stock_history");
        jdbc.update("DELETE FROM product_image");
        jdbc.update("DELETE FROM product_option");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM category WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM category");
    }

    /** 옵션마다 재고를 받아 상품 1개 저장, 옵션 ID 목록 반환 */
    private List<Long> options(ProductStatus status, int... stocks) {
        Product product = Product.builder()
                .storeId(1L).categoryId(shirt.getId()).name("리넨 셔츠")
                .price(10_000L).discountRate(0).material("리넨").origin("대한민국")
                .option1Name("사이즈").status(status)
                .build();
        for (int i = 0; i < stocks.length; i++) {
            product.addOption(ProductOption.of("S" + i, null, 0, stocks[i]));
        }
        product.addImage(ProductImage.of("products/1/main.jpg", 0));
        return productRepository.save(product).getOptions().stream().map(ProductOption::getId).toList();
    }

    private int stock(long optionId) {
        return jdbc.queryForObject("SELECT stock FROM product_option WHERE product_option_id = ?", Integer.class, optionId);
    }

    private int historyCount(String type, String sagaId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE type = ? AND ref_key = ?", Integer.class, type, sagaId);
    }

    private ResultActions reserve(String json) throws Exception {
        return mockMvc.perform(post("/internal/stock/reservations").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String sagaId, long... optionIdAndQuantity) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < optionIdAndQuantity.length; i += 2) {
            items.add("{\"productOptionId\":" + optionIdAndQuantity[i] + ",\"quantity\":" + optionIdAndQuantity[i + 1] + "}");
        }
        return "{\"sagaId\":\"" + sagaId + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    @Nested
    @DisplayName("예약 성공 · 멱등")
    class Reserve {

        @Test
        @DisplayName("전부 가능 — 200, 옵션마다 재고 차감 + RESERVE 기록, 응답은 옵션 ID 오름차순")
        void reserve_allAvailable_decreasesStockAndRecords() throws Exception {
            // given
            List<Long> ids = options(ProductStatus.ON_SALE, 10, 5);

            // when — 요청은 큰 ID 먼저
            reserve(body("saga-1", ids.get(1), 5, ids.get(0), 2))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sagaId").value("saga-1"))
                    .andExpect(jsonPath("$.data.items[0].productOptionId").value(ids.get(0)))
                    .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.data.items[1].productOptionId").value(ids.get(1)))
                    .andExpect(jsonPath("$.data.items[1].quantity").value(5));
            assertThat(stock(ids.get(0))).isEqualTo(8);
            assertThat(stock(ids.get(1))).isZero(); // 딱 맞는 수량은 통과
            assertThat(historyCount("RESERVE", "saga-1")).isEqualTo(2);
        }

        @Test
        @DisplayName("같은 sagaId 재요청 — 200 처음과 같은 응답, 재고는 한 번만 빠짐")
        void reserve_sameSagaAgain_returnsFirstResponse() throws Exception {
            // given
            long id = options(ProductStatus.ON_SALE, 10).getFirst();
            reserve(body("saga-1", id, 3)).andExpect(status().isOk());

            // when · then
            reserve(body("saga-1", id, 3))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].quantity").value(3));
            assertThat(stock(id)).isEqualTo(7);
            assertThat(historyCount("RESERVE", "saga-1")).isEqualTo(1);
        }

        @Test
        @DisplayName("재고 0 인 옵션도 구매 가능 판단은 통과 — 수량 부족으로만 거절")
        void reserve_soldOut_insufficientNotPurchasable() throws Exception {
            long id = options(ProductStatus.ON_SALE, 0).getFirst();
            reserve(body("saga-1", id, 1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        }
    }

    @Nested
    @DisplayName("거절 — 전체 롤백 (재고 · 원장 그대로)")
    class Reject {

        @Test
        @DisplayName("하나라도 재고 부족 — 409 STOCK_INSUFFICIENT, 앞 옵션 차감도 되돌림")
        void reserve_oneInsufficient_rollsBackAll() throws Exception {
            // given
            List<Long> ids = options(ProductStatus.ON_SALE, 10, 1);

            // when · then
            reserve(body("saga-1", ids.get(0), 2, ids.get(1), 2))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
            assertThat(stock(ids.get(0))).isEqualTo(10);
            assertThat(stock(ids.get(1))).isEqualTo(1);
            assertThat(historyCount("RESERVE", "saga-1")).isZero();
        }

        @Test
        @DisplayName("해제 표식 뒤 늦은 예약 — 409 STOCK_RESERVATION_RELEASED, 재고 그대로")
        void reserve_afterReleaseMarker_rejected() throws Exception {
            // given — order 가 타임아웃으로 해제를 먼저 보내 표식(수량 0)이 남은 상태 (2절)
            long id = options(ProductStatus.ON_SALE, 10).getFirst();
            jdbc.update("INSERT INTO stock_history (product_option_id, type, ref_key, quantity) VALUES (?, 'RELEASE', 'saga-1', 0)", id);

            // when · then
            reserve(body("saga-1", id, 2))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STOCK_RESERVATION_RELEASED"));
            assertThat(stock(id)).isEqualTo(10);
            assertThat(historyCount("RESERVE", "saga-1")).isZero();
        }

        @Test
        @DisplayName("판매중지 · 강제 판매중지 · 카테고리 꺼짐 · 옵션 삭제 — 409 PRODUCT_NOT_PURCHASABLE")
        void reserve_notPurchasable_rejected() throws Exception {
            // given
            long ok = options(ProductStatus.ON_SALE, 10).getFirst();
            long stopped = options(ProductStatus.STOPPED, 10).getFirst();
            long deleted = options(ProductStatus.ON_SALE, 10).getFirst();
            jdbc.update("UPDATE product_option SET deleted_at = NOW(6) WHERE product_option_id = ?", deleted);
            long blocked = options(ProductStatus.ON_SALE, 10).getFirst();
            jdbc.update("UPDATE product p JOIN product_option o USING (product_id) SET p.status = 'BLOCKED' WHERE o.product_option_id = ?", blocked);

            // when · then — 하나만 섞여도 전체 거절
            for (long bad : List.of(stopped, deleted, blocked)) {
                reserve(body("saga-" + bad, ok, 1, bad, 1))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_PURCHASABLE"));
            }
            shirt.changeActive(false);
            categoryRepository.save(shirt);
            reserve(body("saga-category", ok, 1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODUCT_NOT_PURCHASABLE"));
            assertThat(stock(ok)).isEqualTo(10);
        }

        @Test
        @DisplayName("없는 옵션 — 404 PRODUCT_OPTION_NOT_FOUND")
        void reserve_missingOption_notFound() throws Exception {
            long id = options(ProductStatus.ON_SALE, 10).getFirst();
            reserve(body("saga-1", id, 1, 999999L, 1))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODUCT_OPTION_NOT_FOUND"));
            assertThat(stock(id)).isEqualTo(10);
        }

        @Test
        @DisplayName("items 비었음 · 수량 0 · 같은 옵션 중복 · sagaId 없음 — 400 INVALID_INPUT")
        void reserve_invalidInput_badRequest() throws Exception {
            long id = options(ProductStatus.ON_SALE, 10).getFirst();
            for (String json : List.of(
                    "{\"sagaId\":\"saga-1\",\"items\":[]}",
                    body("saga-1", id, 0),
                    body("saga-1", id, 1, id, 2),
                    body(" ", id, 1),
                    "{\"items\":[{\"productOptionId\":" + id + ",\"quantity\":1}]}")) {
                reserve(json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            }
            assertThat(stock(id)).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("동시 요청 (옵션 행 잠금)")
    class Concurrency {

        /** 준비된 작업을 동시에 출발시켜 결과(성공 sagaId 또는 오류 코드)를 모은다 */
        private List<String> runTogether(List<Callable<String>> tasks) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (Callable<String> task : tasks) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        try {
                            return task.call();
                        } catch (BusinessException e) {
                            return e.getErrorCode().getCode();
                        }
                    }));
                }
                start.countDown();
                List<String> results = new ArrayList<>();
                for (Future<String> f : futures) {
                    results.add(f.get());
                }
                return results;
            } finally {
                pool.shutdown();
            }
        }

        @Test
        @DisplayName("남은 재고 3개에 서로 다른 주문 5건이 1개씩 — 3건만 성공, 재고 0, 초과 판매 없음")
        void reserve_concurrentOrders_noOversell() throws Exception {
            // given
            long id = options(ProductStatus.ON_SALE, 3).getFirst();
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String sagaId = "saga-" + i;
                tasks.add(() -> stockReservationService.reserve(
                        new StockReserveRequest(sagaId, List.of(new StockItem(id, 1)))).sagaId());
            }

            // when
            List<String> results = runTogether(tasks);

            // then
            assertThat(results).filteredOn(r -> r.startsWith("saga-")).hasSize(3);
            assertThat(results).filteredOn("STOCK_INSUFFICIENT"::equals).hasSize(2);
            assertThat(stock(id)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE type = 'RESERVE'", Integer.class)).isEqualTo(3);
        }

        @Test
        @DisplayName("같은 sagaId 가 동시에 3번 — 모두 200, 재고는 한 번만 빠짐 (유니크 충돌 없음)")
        void reserve_sameSagaConcurrently_appliedOnce() throws Exception {
            // given
            long id = options(ProductStatus.ON_SALE, 10).getFirst();
            Callable<String> task = () -> stockReservationService.reserve(
                    new StockReserveRequest("saga-dup", List.of(new StockItem(id, 4)))).sagaId();

            // when
            List<String> results = runTogether(List.of(task, task, task));

            // then
            assertThat(results).containsOnly("saga-dup");
            assertThat(stock(id)).isEqualTo(6);
            assertThat(historyCount("RESERVE", "saga-dup")).isEqualTo(1);
        }

        @Test
        @DisplayName("옵션 두 개를 서로 반대 순서로 담은 주문 2건 — 데드락 없이 둘 다 성공 (ID 오름차순 잠금)")
        void reserve_oppositeOrder_noDeadlock() throws Exception {
            // given
            List<Long> ids = options(ProductStatus.ON_SALE, 10, 10);
            Callable<String> ab = () -> stockReservationService.reserve(new StockReserveRequest("saga-ab",
                    List.of(new StockItem(ids.get(0), 1), new StockItem(ids.get(1), 1)))).sagaId();
            Callable<String> ba = () -> stockReservationService.reserve(new StockReserveRequest("saga-ba",
                    List.of(new StockItem(ids.get(1), 1), new StockItem(ids.get(0), 1)))).sagaId();

            // when
            List<String> results = runTogether(List.of(ab, ba));

            // then
            assertThat(results).containsExactly("saga-ab", "saga-ba");
            assertThat(stock(ids.get(0))).isEqualTo(8);
            assertThat(stock(ids.get(1))).isEqualTo(8);
        }
    }
}
