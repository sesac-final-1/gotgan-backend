package com.sesac.gojigi.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * review-service (:8087) — 리뷰 · 판매자 답글 · 상품문의 · 리뷰 작성 자격
 * 담당: jongbeen97 · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
public class ReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewApplication.class, args);
    }
}
