package com.sesac.gojigi.review.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.review.TestcontainersConfiguration;
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
 * 시드는 INSERT IGNORE 라 NOT NULL 누락 · UNIQUE 중복이 오류 없이 경고로 끝나고 행이 빠진다. 그래서 들어간 행을 직접 센다.
 * 테이블에 NOT NULL 컬럼을 추가하면 scenario.mjs · gen.mjs 도 함께 고칠 것.
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
    @DisplayName("작성 자격 = 구매확정 상품주문 8건(기한 = 구매확정 + 30일), 리뷰 6건 중 블라인드 1 · 판매자 답글 3")
    void seed_insertsEligibilitiesAndReviews() {
        assertThat(count("review_eligibility")).isEqualTo(8);
        assertThat(count("review_eligibility WHERE expires_at = confirmed_at + INTERVAL 30 DAY AND expires_at > NOW()")).isEqualTo(8);
        assertThat(count("review")).isEqualTo(6);
        assertThat(count("review WHERE is_blinded = TRUE AND blind_reason IS NOT NULL AND blinded_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("review WHERE reply_content IS NOT NULL AND replied_at IS NOT NULL")).isEqualTo(3);
        assertThat(count("review r JOIN review_eligibility e ON e.review_eligibility_id = r.review_eligibility_id"
                + " WHERE r.order_item_id <> e.order_item_id OR r.user_id <> e.user_id OR r.writer_display_name <> e.buyer_display_name"))
                .isZero();   // 리뷰는 자격과 같은 상품주문 · 작성자 · 표시 이름
        assertThat(count("review WHERE created_at > NOW() OR replied_at > NOW() OR blinded_at > NOW()")).isZero();
    }

    @Test
    @DisplayName("buyer1(user 4)은 작성 가능한 리뷰가 2건 남는다 (1006 · 1007)")
    void seed_buyer1_hasWritableEligibilities() {
        assertThat(jdbc.queryForList("""
                SELECT e.order_item_id FROM review_eligibility e LEFT JOIN review r ON r.order_item_id = e.order_item_id
                WHERE e.user_id = 4 AND r.review_id IS NULL AND e.expires_at > NOW() ORDER BY e.order_item_id
                """, Long.class)).containsExactly(1006L, 1007L);
    }

    @Test
    @DisplayName("상품 문의 7건 — 비밀글 2 · 답변완료 4, 스토어 1 미답변 2 · 스토어 2 미답변 1")
    void seed_insertsInquiries() {
        assertThat(count("product_inquiry")).isEqualTo(7);
        assertThat(count("product_inquiry WHERE is_secret = TRUE")).isEqualTo(2);
        assertThat(count("product_inquiry WHERE answer_content IS NOT NULL AND answered_at IS NOT NULL")).isEqualTo(4);
        assertThat(count("product_inquiry WHERE store_id = 1 AND answered_at IS NULL")).isEqualTo(2);
        assertThat(count("product_inquiry WHERE store_id = 2 AND answered_at IS NULL")).isEqualTo(1);
        assertThat(count("product_inquiry WHERE answered_at < created_at")).isZero();
    }

    private int count(String from) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Integer.class);
    }
}
