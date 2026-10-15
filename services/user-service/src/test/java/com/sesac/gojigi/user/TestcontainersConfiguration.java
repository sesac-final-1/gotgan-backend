package com.sesac.gojigi.user;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * DB 가 필요한 테스트의 MySQL — 클러스터와 같은 8.4, 시간대 · 문자셋도 운영과 동일 (컨벤션 2-10 · 3-5, H2 사용 안 함).
 * 스키마는 Flyway(db/migration)가 만들고 ddl-auto: validate 로 엔티티와 대조한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4")
                .withDatabaseName("userdb")
                .withCommand("--default-time-zone=+09:00",
                        "--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
    }
}
