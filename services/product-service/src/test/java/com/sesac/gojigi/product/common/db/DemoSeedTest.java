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
    @DisplayName("시드 뒤 새로 만드는 카테고리는 604 부터 — 수수료 시드 번호(1~6)와 겹치지 않는다")
    void seed_nextCategoryId_afterSeed() {
        jdbc.update("INSERT INTO category (parent_id, name, sort_order, is_active) VALUES (NULL, '테스트', 7, FALSE)");

        assertThat(jdbc.queryForObject("SELECT MAX(category_id) FROM category", Long.class)).isEqualTo(604L);
    }
}
