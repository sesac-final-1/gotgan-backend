package com.sesac.gojigi.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-service (:8083) — 카테고리 · 상품 · 옵션 · 재고 · 이미지 메타 · QueryDSL 검색
 * 담당: VoC-100 · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
