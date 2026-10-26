package com.sesac.gojigi.settlement.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.settlement.TestcontainersConfiguration;
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
    @DisplayName("스토어 스냅샷 9 · 정산서 7(지급예정 · 자동 보류 · 지급 완료) · 항목 8 = order 시드 구매확정분, 합계 = 항목 합")
    void seed_insertsConsistentRows() {
        assertThat(count("COUNT(*) FROM settlement_store")).as("settlement_store").isEqualTo(9);
        assertThat(count("COUNT(*) FROM settlement")).as("settlement").isEqualTo(7);
        assertThat(count("COUNT(*) FROM settlement_item")).as("settlement_item").isEqualTo(8);
        assertThat(count("COUNT(*) FROM payout")).as("payout").isEqualTo(3);
        assertThat(count("COUNT(*) FROM settlement WHERE status = 'HELD' AND store_id = 15")).as("정지 스토어 자동 보류").isEqualTo(1);
        assertThat(count("COUNT(*) FROM settlement s WHERE s.sales_amt <> (SELECT SUM(i.item_amt) FROM settlement_item i WHERE i.settlement_id = s.settlement_id) OR s.payout_amt <> (SELECT SUM(i.item_amt - i.commission_amt - i.payment_fee_amt) FROM settlement_item i WHERE i.settlement_id = s.settlement_id)")).as("정산서 합계 = 항목 합").isEqualTo(0);
        assertThat(count("COUNT(*) FROM settlement s JOIN payout p ON p.settlement_id = s.settlement_id WHERE s.status <> 'PAID' OR p.payout_amt <> s.payout_amt")).as("지급액 = 지급 기록").isEqualTo(0);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT " + fromWhere, Integer.class);
    }
}
