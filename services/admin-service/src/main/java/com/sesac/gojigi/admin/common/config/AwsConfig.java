package com.sesac.gojigi.admin.common.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK v2 클라이언트 — 알림 센터(NTF-08) 이벤트 구독 · 알림함. 자격 증명은 기본 체인(클러스터: IRSA sesac-gojigi-admin-irsa, 로컬: ~/.aws 프로필).
 * 노드 IAM 역할은 IMDS hop limit 1 로 막혀 있어 IRSA 가 없으면 자격 증명이 없다 (인수인계 문서 9장).
 */
@Configuration
public class AwsConfig {

    @Bean
    public SqsClient sqsClient(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }

    /** 알림함 — 화면 요청 경로라 호출 전체 3초 · 시도 1초로 끊는다 */
    @Bean
    public DynamoDbClient dynamoDbClient(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return DynamoDbClient.builder().region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(1)))
                .build();
    }
}
