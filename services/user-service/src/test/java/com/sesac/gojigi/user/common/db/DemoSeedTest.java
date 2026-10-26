package com.sesac.gojigi.user.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.TestcontainersConfiguration;
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
    @DisplayName("로그인 계정 6 · 목업 구매자 11 · 스토어 대표자 7, 포인트 잔액은 적립 이력 합과 같다")
    void seed_insertsConsistentRows() {
        assertThat(count("COUNT(*) FROM users")).as("users").isEqualTo(24);
        assertThat(count("COUNT(*) FROM users WHERE user_id BETWEEN 1 AND 6")).as("로그인 계정 1~6").isEqualTo(6);
        assertThat(count("COUNT(*) FROM users WHERE status = 'SUSPENDED' AND suspended_until IS NOT NULL")).as("이용정지 1").isEqualTo(1);
        assertThat(count("COUNT(*) FROM users u WHERE u.point_balance <> (SELECT COALESCE(SUM(p.amount), 0) FROM point_history p WHERE p.user_id = u.user_id) AND EXISTS (SELECT 1 FROM point_history p WHERE p.user_id = u.user_id)")).as("포인트 잔액 = 이력 합(이력이 있는 회원)").isEqualTo(0);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT " + fromWhere, Integer.class);
    }
}
