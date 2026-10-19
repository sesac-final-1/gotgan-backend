package com.sesac.gojigi.store.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
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
 * 시연 시드(db/seed/R__demo_seed.sql) 검증 — local · k8s 프로필처럼 V1 · V2 다음에 시드까지 실행 (담당 박지연).
 * 시드는 INSERT IGNORE 라 NOT NULL 누락 · UNIQUE 중복이 오류 없이 경고로 끝나고 행이 빠진다. 그래서 들어간 행을 직접 센다.
 * 테이블에 NOT NULL 컬럼을 추가하면 시드도 함께 고칠 것.
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
    @DisplayName("시드가 신청서 · 이력 · 스토어 · 상태 이력을 빠짐없이 넣는다 (시연 판매자 2명)")
    void seed_afterV2_insertsAllRows() {
        assertThat(count("store_application")).isEqualTo(2);
        assertThat(count("application_history")).isEqualTo(4);
        assertThat(count("store")).isEqualTo(2);
        assertThat(count("store_status_history")).isEqualTo(2);
    }

    @Test
    @DisplayName("시드 스토어는 user_id · store_id 가 Keycloak 시드와 같고, 스토어 주소는 신청서와 같다")
    void seed_stores_matchKeycloakAndApplication() {
        List<Map<String, Object>> stores = jdbc.queryForList("""
                SELECT s.store_id, s.user_id, s.slug, a.slug AS application_slug, s.grade
                FROM store s JOIN store_application a ON a.store_application_id = s.store_application_id
                ORDER BY s.store_id
                """);

        assertThat(stores).extracting(row -> row.get("user_id")).containsExactly(2L, 3L);
        assertThat(stores).extracting(row -> row.get("slug")).containsExactly("gotgan-sanghoe", "gotgan-living");
        assertThat(stores).allSatisfy(row -> {
            assertThat(row.get("slug")).isEqualTo(row.get("application_slug"));
            assertThat(row.get("grade")).isEqualTo("SPROUT");
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
