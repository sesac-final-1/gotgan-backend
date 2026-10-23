package com.sesac.gojigi.order.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchedulingConfigTest {

    @Test
    @DisplayName("ShedLock 잠금 저장소는 orderdb 의 shedlock 테이블(JdbcTemplate)")
    void lockProvider_jdbcTemplate() {
        assertThat(new SchedulingConfig().lockProvider(mock(DataSource.class)))
                .isInstanceOf(JdbcTemplateLockProvider.class);
    }
}
