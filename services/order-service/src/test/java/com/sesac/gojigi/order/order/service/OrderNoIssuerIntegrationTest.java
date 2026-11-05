package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 주문번호 채번(부하 테스트 경-1) — 실제 MySQL 8.4 에서 동시 발급 · 날짜 전환 · 기존 주문 이어 받기 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderNoIssuer.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 발급마다 실제로 커밋 (주문 트랜잭션 밖과 같은 조건)
class OrderNoIssuerIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired private OrderNoIssuer issuer;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock clock;

    private void today(LocalDate date) {
        given(clock.getZone()).willReturn(KST);
        given(clock.instant()).willReturn(date.atTime(12, 0).atZone(KST).toInstant());
    }

    @Test
    @DisplayName("그날 첫 주문부터 100건이 동시에 와도 번호가 겹치지 않고 1 ~ 100 으로 빠짐없이 나간다")
    void next_concurrent_noDuplicates() throws Exception {
        // given
        today(LocalDate.of(2026, 11, 5));
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> issue = () -> {
            start.await();
            return issuer.next();
        };
        List<String> expected = IntStream.rangeClosed(1, 100).mapToObj(n -> String.format("20261105-%06d", n)).toList();

        // when
        List<String> issued;
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            List<Future<String>> futures = IntStream.range(0, 100).mapToObj(i -> pool.submit(issue)).toList();
            start.countDown();
            issued = futures.stream().map(OrderNoIssuerIntegrationTest::result).toList();
        }

        // then
        assertThat(issued).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static String result(Future<String> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("같은 날은 이어서, 날짜가 바뀌면 1 부터")
    void next_dateChange_restarts() {
        // given
        today(LocalDate.of(2026, 11, 6));
        assertThat(issuer.next()).isEqualTo("20261106-000001");
        assertThat(issuer.next()).isEqualTo("20261106-000002");

        // when · then
        today(LocalDate.of(2026, 11, 7));
        assertThat(issuer.next()).isEqualTo("20261107-000001");
    }

    @Test
    @DisplayName("V7 초기값 — 기존 주문의 날짜별 최대 순번 다음 번호부터 발급한다")
    void migration_continuesFromExistingOrders() throws IOException {
        // given — 배포 전에 만들어진 주문 (시드 대역 포함)
        insertOrder("20250101-000007");
        insertOrder("20250101-000012");
        insertOrder("20250102-901001");
        jdbc.update("DELETE FROM order_no_sequence");

        // when — V7 의 초기값 INSERT
        String migration = new ClassPathResource("db/migration/V7__order_no_sequence.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.update(migration.substring(migration.indexOf("INSERT INTO order_no_sequence")).replace(";", ""));

        // then
        today(LocalDate.of(2025, 1, 1));
        assertThat(issuer.next()).isEqualTo("20250101-000013");
        today(LocalDate.of(2025, 1, 2));
        assertThat(issuer.next()).isEqualTo("20250102-901002");
    }

    private void insertOrder(String orderNo) {
        jdbc.update("""
                INSERT INTO orders (buyer_id, order_no, orderer_name, orderer_display_name, orderer_email, receiver_name,
                  receiver_phone, zipcode, address1, total_product_amt, total_shipping_fee, pay_amt)
                VALUES (4, ?, '홍길동', '홍*동', 'b@e.com', '홍길동', '010-1234-5678', '06236', '서울', 1000, 0, 1000)""", orderNo);
    }
}
