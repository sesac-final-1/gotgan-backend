package com.sesac.gojigi.product.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK v2 클라이언트 — 자격 증명은 기본 체인(클러스터: IRSA 웹 아이덴티티 토큰, 로컬: ~/.aws 프로필).
 * 노드 IAM 역할은 IMDS hop limit 1 로 막혀 있어 IRSA 가 없으면 자격 증명이 없다 (store AwsConfig 와 같음).
 */
@Configuration
public class AwsConfig {

    /** 구독 큐 롱폴링 (product-store-events, IRSA sqs 수신 권한) */
    @Bean
    public SqsClient sqsClient(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }

    /** 상품 이미지 업로드 presigned PUT (images 버킷 products/*, IRSA s3:PutObject). 서명은 로컬 계산이라 S3 호출 없음 */
    @Bean
    public S3Presigner s3Presigner(@Value("${AWS_REGION:ap-northeast-2}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
