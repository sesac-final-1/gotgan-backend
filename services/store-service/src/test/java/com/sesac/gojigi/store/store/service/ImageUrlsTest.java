package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageUrlsTest {

    @Test
    @DisplayName("키 앞에 base-url 을 붙인다 — base-url 끝의 / 는 하나로, 키가 없으면 null")
    void of_joinsBaseUrlAndKey() {
        // given
        ImageUrls withSlash = new ImageUrls("https://images.example.com/");
        ImageUrls withoutSlash = new ImageUrls("https://images.example.com");

        // when · then
        assertThat(withSlash.of("stores/1/a.jpg")).isEqualTo("https://images.example.com/stores/1/a.jpg");
        assertThat(withoutSlash.of("stores/1/a.jpg")).isEqualTo("https://images.example.com/stores/1/a.jpg");
        assertThat(withoutSlash.of(null)).isNull();
    }
}
