package com.sesac.gojigi.order.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 시연 시드(db/seed/R__demo_seed.sql — scripts/seed-gen 생성 파일) 검증 — local · k8s 프로필처럼 마이그레이션 다음에 시드까지 실행.
 * 시드는 INSERT IGNORE 라 NOT NULL 누락 · UNIQUE 중복이 오류 없이 경고로 끝나고 행이 빠진다. 그래서 들어간 행을 직접 센다 (store DemoSeedTest 와 같은 방식).
 * 숫자는 scripts/seed-gen/scenario.mjs 기준 — 시나리오를 바꾸면 함께 고칠 것
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class DemoSeedTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("주문 14(구매확정 8 · 진행 중 6), 금액 · 상품주문번호 규칙과 시각 순서(결제 < 발송 < 배송완료 < 구매확정)")
    void seed_insertsConsistentRows() {
        assertThat(count("COUNT(*) FROM orders")).as("orders").isEqualTo(14);
        assertThat(count("COUNT(*) FROM order_item")).as("order_item").isEqualTo(14);
        assertThat(count("COUNT(*) FROM order_item WHERE status = 'CONFIRMED' AND confirmed_at > delivered_at AND delivered_at > shipped_at")).as("구매확정 8").isEqualTo(8);
        assertThat(count("COUNT(*) FROM orders WHERE pay_amt <> total_product_amt + total_shipping_fee - COALESCE(used_point, 0)")).as("주문 금액 = 상품 + 배송비").isEqualTo(0);
        assertThat(count("COUNT(*) FROM order_item i JOIN orders o ON o.order_id = i.order_id WHERE i.item_no <> CONCAT(o.order_no, '-01')")).as("상품주문번호 = 주문번호-01").isEqualTo(0);
        assertThat(count("COUNT(*) FROM order_item i WHERE NOT EXISTS (SELECT 1 FROM order_item_history h WHERE h.order_item_id = i.order_item_id)")).as("이력 없는 상품주문").isEqualTo(0);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT " + fromWhere, Integer.class);
    }
}
