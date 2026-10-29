package com.sesac.gojigi.store.common.html;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntroHtmlTest {

    @Test
    @DisplayName("script · on* 속성은 지우고 문단 · 서식 · 링크는 남긴다")
    void sanitize_removesScriptKeepsFormatting() {
        // when
        String sanitized = IntroHtml.sanitize("<p onclick=\"x()\">안녕하세요<script>alert(1)</script> <b>리넨</b></p>");

        // then
        assertThat(sanitized).doesNotContain("script", "onclick", "alert").contains("<p>", "<b>리넨</b>");
    }

    @Test
    @DisplayName("비었거나 공백뿐 · 정화 뒤 남는 게 없으면 null")
    void sanitize_whenBlankOrEmptyAfterSanitize_returnsNull() {
        assertThat(IntroHtml.sanitize(null)).isNull();
        assertThat(IntroHtml.sanitize("   ")).isNull();
        assertThat(IntroHtml.sanitize("<script>alert(1)</script>")).isNull();
    }
}
