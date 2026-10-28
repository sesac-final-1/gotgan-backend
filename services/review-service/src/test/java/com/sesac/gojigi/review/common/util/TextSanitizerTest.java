package com.sesac.gojigi.review.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextSanitizerTest {

    @Test
    @DisplayName("태그와 스크립트는 지우고 글자만 남긴다")
    void plainText_removesTagsAndScripts() {
        assertThat(TextSanitizer.plainText("<b>좋아요</b><script>alert(1)</script> <img src=x onerror=alert(1)>"))
                .isEqualTo("좋아요");
    }

    @Test
    @DisplayName("특수문자는 엔티티로 남기지 않고 원래 글자로 저장한다")
    void plainText_keepsSpecialCharactersReadable() {
        assertThat(TextSanitizer.plainText("가격 & 품질 > 기대, \"재구매\" 할게요 'ㅎㅎ' @곳간"))
                .isEqualTo("가격 & 품질 > 기대, \"재구매\" 할게요 'ㅎㅎ' @곳간");
    }

    @Test
    @DisplayName("엔티티로 쓴 태그도 되돌린 뒤 지운다 (저장 값에 태그가 남지 않음)")
    void plainText_escapedTags_areRemovedToo() {
        assertThat(TextSanitizer.plainText("&lt;b&gt;굵게&lt;/b&gt;")).isEqualTo("굵게");
        assertThat(TextSanitizer.plainText("&amp;lt;script&amp;gt;alert(1)&amp;lt;/script&amp;gt;좋아요")).isEqualTo("좋아요");
    }

    @Test
    @DisplayName("줄바꿈은 지키고 앞뒤 공백은 지운다")
    void plainText_keepsLineBreaksAndStrips() {
        assertThat(TextSanitizer.plainText("  첫 줄\n둘째 줄  ")).isEqualTo("첫 줄\n둘째 줄");
    }

    @Test
    @DisplayName("null · 태그만 있는 값은 null")
    void plainText_nullOrOnlyTags_returnsNull() {
        assertThat(TextSanitizer.plainText(null)).isNull();
        assertThat(TextSanitizer.plainText("<script>alert(1)</script>  ")).isNull();
    }
}
