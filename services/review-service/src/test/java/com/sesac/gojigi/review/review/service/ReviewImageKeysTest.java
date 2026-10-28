package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewImageKeysTest {

    private static final String MINE = "reviews/4/7f3c9e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6.jpg";

    @Test
    @DisplayName("사진 없음(null · 빈 목록)은 빈 목록")
    void check_noImages_returnsEmpty() {
        assertThat(ReviewImageKeys.check(4L, null, Set.of())).isEmpty();
        assertThat(ReviewImageKeys.check(4L, List.of(), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("내 키 3장 · jpg · png · webp 는 순서 그대로 통과")
    void check_ownKeys_pass() {
        List<String> keys = List.of(MINE, "reviews/4/b.png", "reviews/4/c.webp");

        assertThat(ReviewImageKeys.check(4L, keys, Set.of())).containsExactlyElementsOf(keys);
    }

    @ParameterizedTest
    @ValueSource(strings = { "reviews/5/a.jpg", "reviews/44/a.jpg", "products/4/a.jpg", "reviews/4/a.gif",
            "reviews/4/../5/a.jpg", "reviews/4/a.jpg?x=1" })
    @DisplayName("남의 · 다른 위치 · 다른 형식 키는 400 REVIEW_IMAGE_INVALID")
    void check_notOwnKey_throwsInvalid(String key) {
        assertThatThrownBy(() -> ReviewImageKeys.check(4L, List.of(key), Set.of()))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_IMAGE_INVALID)
                .hasMessage("업로드 주소 발급으로 받은 내 사진만 사용할 수 있습니다.");
    }

    @Test
    @DisplayName("4장 · 중복 · null 키는 400 REVIEW_IMAGE_INVALID")
    void check_tooManyDuplicateOrNull_throwsInvalid() {
        assertThatThrownBy(() -> ReviewImageKeys.check(4L, List.of("reviews/4/a.jpg", "reviews/4/b.jpg", "reviews/4/c.jpg",
                "reviews/4/d.jpg"), Set.of()))
                .hasMessage("사진은 최대 3장까지 올릴 수 있습니다.");
        assertThatThrownBy(() -> ReviewImageKeys.check(4L, List.of(MINE, MINE), Set.of()))
                .hasMessage("같은 사진이 중복되었습니다.");
        assertThatThrownBy(() -> ReviewImageKeys.check(4L, Arrays.asList(MINE, null), Set.of()))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_IMAGE_INVALID);
    }

    @Test
    @DisplayName("수정 때 이미 붙어 있는 키는 형식과 상관없이 다시 보낼 수 있다")
    void check_currentKeys_allowed() {
        String legacy = "reviews/4/legacy_key.jpg";

        assertThat(ReviewImageKeys.check(4L, List.of(legacy), Set.of(legacy))).containsExactly(legacy);
    }
}
