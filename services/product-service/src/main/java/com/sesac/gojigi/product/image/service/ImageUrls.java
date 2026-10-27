package com.sesac.gojigi.product.image.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** S3 키 → 조회 URL (product.md 공통 "이미지") — IMAGES_BASE_URL/{key}. 키가 없으면 null */
@Component
public class ImageUrls {

    private final String baseUrl;

    public ImageUrls(@Value("${app.images.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String of(String s3Key) {
        return s3Key == null ? null : baseUrl + "/" + s3Key;
    }
}
