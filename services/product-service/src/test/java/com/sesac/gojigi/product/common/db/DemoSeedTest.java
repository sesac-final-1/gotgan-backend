package com.sesac.gojigi.product.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import java.util.List;
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
 * 시연 시드(db/seed/R__demo_seed.sql) 검증 — local · k8s 프로필처럼 V1 · V2 다음에 시드까지 실행.
 * 시드는 INSERT IGNORE 라 NOT NULL 누락 · UNIQUE 중복이 오류 없이 경고로 끝나고 행이 빠진다. 그래서 들어간 행을 직접 센다 (store DemoSeedTest 와 같은 방식).
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
    @DisplayName("카테고리 시드 — 상위 6 · 하위 28, 모두 사용 중이고 하위 ID 는 상위 × 100 + 순번")
    void seed_categories() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id IS NULL AND is_active", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id IS NOT NULL AND is_active", Integer.class)).isEqualTo(28);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id IS NOT NULL AND category_id DIV 100 <> parent_id",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("상위 ID 1~6 의 이름 · 정렬이 product.md 확정표와 같다 — settlement 수수료 · store 신청서가 같은 번호를 쓴다")
    void seed_topCategories_matchContract() {
        List<String> tops = jdbc.queryForList("SELECT CONCAT(category_id, ':', name, ':', sort_order) FROM category WHERE parent_id IS NULL ORDER BY category_id",
                String.class);

        assertThat(tops).containsExactly("1:식품:4", "2:리빙·인테리어:3", "3:패션의류:1", "4:신발·가방:2", "5:뷰티:5", "6:디지털·가전:6");
    }

    @Test
    @DisplayName("상품 시드 — 39개 모두 하위 카테고리 · 옵션 ≥ 1 · 이미지 3장(대표 1), 정지 스토어(15) 상품만 비노출, order 시드 상품주문과 같은 번호 · 판매가")
    void seed_products() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(39);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN category c ON c.category_id = p.category_id WHERE c.parent_id IS NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p WHERE NOT EXISTS (SELECT 1 FROM product_option o WHERE o.product_id = p.product_id)",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE sort_order = 0", Integer.class)).isEqualTo(39);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image", Integer.class)).isEqualTo(39 * 3);
        assertThat(jdbc.queryForList("SELECT DISTINCT store_id FROM product WHERE NOT is_store_active", Long.class)).containsExactly(15L);
        // 판매가 = price × (100 − 할인율) / 100 을 10원 단위 반올림 — 서버 규칙과 같게
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE sale_price <> ROUND(price * (100 - discount_rate) / 100, -1)",
                Integer.class)).isZero();
        // order 시드 상품주문 1001 — 상품 101 · 옵션 10101 · 판매가 26,980원 (서귀포 노지 햇감귤 32,900원 18%)
        assertThat(jdbc.queryForObject("SELECT CONCAT(p.name, ':', p.sale_price, ':', o.option1_value) FROM product p "
                + "JOIN product_option o ON o.product_id = p.product_id WHERE o.product_option_id = 10101", String.class))
                .isEqualTo("서귀포 노지 햇감귤 5kg:26980:로얄과 (S~M)");
    }

    @Test
    @DisplayName("시드 뒤 새로 만드는 카테고리는 604 부터 — 수수료 시드 번호(1~6)와 겹치지 않는다")
    void seed_nextCategoryId_afterSeed() {
        jdbc.update("INSERT INTO category (parent_id, name, sort_order, is_active) VALUES (NULL, '테스트', 7, FALSE)");

        assertThat(jdbc.queryForObject("SELECT MAX(category_id) FROM category", Long.class)).isEqualTo(604L);
    }
}
