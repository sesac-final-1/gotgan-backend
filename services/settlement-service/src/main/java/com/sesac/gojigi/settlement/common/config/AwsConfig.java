package com.sesac.gojigi.settlement.common.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK v2 클라이언트 — 자격 증명은 기본 체인(클러스터: IRSA 웹 아이덴티티 토큰, 로컬: ~/.aws 프로필).
 * 노드 IAM 역할은 IMDS hop limit 1 로 막혀 있어 IRSA 가 없으면 자격 증명이 없다 (인수인계 문서 9장).
 */
@Configuration
public class AwsConfig {

    @Bean
    public SqsClient sqsClient(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public SnsClient snsClient(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return SnsClient.builder().region(Region.of(region)).build();
    }

    /** 명세서 CSV 존재 확인(HeadObject — IRSA s3:GetObject 로 허용). 판매자 요청 경로라 호출 전체 3초 · 시도 1초로 끊는다 */
    @Bean
    public S3Client s3Client(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return S3Client.builder().region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(1)))
                .build();
    }

    /** 정산 명세서 다운로드 presigned GET (reports 버킷, IRSA s3:GetObject) */
    @Bean
    public S3Presigner s3Presigner(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
