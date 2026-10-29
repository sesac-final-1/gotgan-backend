package com.sesac.gojigi.store.store.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** S3 키 → 조회 URL — IMAGES_BASE_URL/{key} (product ImageUrls 와 같은 규칙, 키에 stores/ 폴더 포함). 키가 없으면 null */
@Component
public class ImageUrls {

    private final String baseUrl;

    public ImageUrls(@Value("${app.images.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String of(String key) {
        return key == null ? null : baseUrl + "/" + key;
    }
}
