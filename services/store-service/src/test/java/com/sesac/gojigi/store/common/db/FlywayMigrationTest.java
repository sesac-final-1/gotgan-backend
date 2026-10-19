package com.sesac.gojigi.store.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * storedb 마이그레이션 검증 — 실제 MySQL(Testcontainers)에 Flyway V1 · V2 적용 (담당 박지연).
 * V2: 스토어 주소(slug) · 표시 색 · 등급 · 택배사 · 출고 안내 · 월 예상 매출 · 운영 채널, slug · 스토어명 UNIQUE.
 * 마이그레이션을 추가하면 컬럼 순서 기대값을 함께 고칠 것.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long applicationId;

    @BeforeEach
    void setUp() {
        applicationId = insertApplication(1L, "orot-linen");
    }

    @Test
    @DisplayName("V2 까지 적용되고 새 컬럼이 컨벤션 3-9 순서 자리에 들어간다")
    void migrate_upToV2_addsColumnsInConventionOrder() {
        assertThat(jdbc.queryForObject(
                "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isEqualTo(2);
        assertThat(columns("store")).containsExactly(
                "store_id", "store_application_id", "user_id", "store_name", "slug", "intro", "image_key", "color",
                "cs_phone", "shipping_fee", "free_shipping_threshold", "courier", "dispatch_note", "grade",
                "status", "status_reason", "created_at", "updated_at", "deleted_at");
        assertThat(columns("store_application")).containsSubsequence(
                "store_name", "slug", "intro", "representative_products", "expected_monthly_sales", "sales_channel",
                "bank_name");
    }

    @Test
    @DisplayName("스토어 생성 시 표시 색 · 등급은 기본값, 택배사 · 출고 안내는 비어 있다 (승인 직후)")
    void insertStore_withoutDisplayColumns_appliesDefaults() {
        insertStore(applicationId, 1L, "오롯이 리넨", "orot-linen");

        Map<String, Object> row = jdbc.queryForMap("SELECT color, grade, courier, dispatch_note FROM store");
        assertThat(row.get("color")).isEqualTo("#5b6b4a");
        assertThat(row.get("grade")).isEqualTo("SPROUT");
        assertThat(row.get("courier")).isNull();
        assertThat(row.get("dispatch_note")).isNull();
    }

    @Test
    @DisplayName("스토어 주소 없이는 신청서도 스토어도 저장할 수 없다")
    void insert_withoutSlug_isRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO store (store_application_id, user_id, store_name, cs_phone) VALUES (?, 1, '이름', '010')",
                applicationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Field 'slug' doesn't have a default value");
        assertThatThrownBy(() -> insertApplication(2L, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Column 'slug' cannot be null");
    }

    @Test
    @DisplayName("같은 스토어 주소는 대소문자만 달라도 두 번째 스토어를 막는다 (uk_store_slug)")
    void insertStore_duplicateSlugIgnoringCase_isRejected() {
        insertStore(applicationId, 1L, "오롯이 리넨", "orot-linen");
        long second = insertApplication(2L, "Orot-Linen");

        assertThatThrownBy(() -> insertStore(second, 2L, "다른 이름", "Orot-Linen"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 스토어명은 두 번째 스토어를 막는다 (uk_store_store_name)")
    void insertStore_duplicateStoreName_isRejected() {
        insertStore(applicationId, 1L, "오롯이 리넨", "orot-linen");
        long second = insertApplication(2L, "other");

        assertThatThrownBy(() -> insertStore(second, 2L, "오롯이 리넨", "other"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                String.class, table);
    }

    private long insertApplication(long userId, String slug) {
        jdbc.update("""
                INSERT INTO store_application (user_id, category_id, biz_type, company_name, owner_name, biz_reg_no,
                    ecommerce_reg_no, manager_phone, manager_email, business_address, store_name, slug,
                    representative_products, bank_name, account_no, account_holder, terms_agreed_at, status_changed_at)
                VALUES (?, 1, 'INDIVIDUAL', '상호', '대표', '214-87-30125', '2026-서울-0001', '010-0000-0000',
                    'seller@example.com', '서울', '신청 스토어', ?, '[]', '은행', '123', '예금주', NOW(6), NOW(6))
                """, userId, slug);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertStore(long storeApplicationId, long userId, String storeName, String slug) {
        jdbc.update("INSERT INTO store (store_application_id, user_id, store_name, slug, cs_phone) VALUES (?, ?, ?, ?, '010')",
                storeApplicationId, userId, storeName, slug);
    }
}
