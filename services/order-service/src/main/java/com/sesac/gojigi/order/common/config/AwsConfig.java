package com.sesac.gojigi.order.common.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * AWS SDK v2 클라이언트 — 자격 증명은 기본 체인(클러스터: IRSA 웹 아이덴티티 토큰, 로컬: ~/.aws 프로필). settlement 와 같은 설정.
 */
@Configuration
public class AwsConfig {

    @Bean
    public SnsClient snsClient(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        // 아웃박스 발행 — 느린 SNS 가 커밋 후 처리 · 발행기를 붙잡지 않게 호출 전체 3초 · 시도 1초 (실패는 아웃박스가 재발행)
        return SnsClient.builder().region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(1)))
                .build();
    }
}
