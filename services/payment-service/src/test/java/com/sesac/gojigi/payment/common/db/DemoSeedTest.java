package com.sesac.gojigi.payment.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.payment.TestcontainersConfiguration;
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
    @DisplayName("주문 시드와 같은 결제 14건 — 포트원 결제가 없는 seed- 결제")
    void seed_insertsConsistentRows() {
        assertThat(count("COUNT(*) FROM payment WHERE status = 'PAID' AND portone_payment_id LIKE 'seed-pay-%'")).as("payment").isEqualTo(14);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT " + fromWhere, Integer.class);
    }
}
