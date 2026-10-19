package com.sesac.gojigi.product.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * productdb 마이그레이션 검증 — 실제 MySQL(Testcontainers)에 Flyway V1 · V2 적용 (담당 박지연).
 * V2: 카테고리 영문명(선택) · 상품정보 제공고시 소재 · 원산지(필수).
 * 마이그레이션을 추가하면 컬럼 순서 기대값을 함께 고칠 것.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V2 까지 적용되고 새 컬럼이 컨벤션 3-9 순서 자리에 들어간다")
    void migrate_upToV2_addsColumnsInConventionOrder() {
        assertThat(jdbc.queryForObject(
                "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isEqualTo(2);
        assertThat(columns("category")).containsExactly(
                "category_id", "parent_id", "name", "name_en", "sort_order", "is_active", "created_at", "updated_at");
        assertThat(columns("product")).containsSubsequence(
                "sale_price", "description", "material", "origin", "option1_name");
    }

    @Test
    @DisplayName("카테고리 영문명은 없어도 저장된다 (선택 값)")
    void insertCategory_withoutNameEn_isAllowed() {
        jdbc.update("INSERT INTO category (name) VALUES ('패션의류')");

        assertThat(jdbc.queryForObject("SELECT name_en FROM category", String.class)).isNull();
    }

    @Test
    @DisplayName("소재 · 원산지 없이는 상품을 저장할 수 없다 (상품정보 제공고시 필수)")
    void insertProduct_withoutMaterialOrOrigin_isRejected() {
        jdbc.update("INSERT INTO category (name) VALUES ('패션의류')");
        long categoryId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product (category_id, store_id, name, price, sale_price, origin) VALUES (?, 1, '셔츠', 1000, 1000, '대한민국')",
                categoryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Field 'material' doesn't have a default value");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product (category_id, store_id, name, price, sale_price, material) VALUES (?, 1, '셔츠', 1000, 1000, '리넨 100%')",
                categoryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Field 'origin' doesn't have a default value");
    }

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                String.class, table);
    }
}
