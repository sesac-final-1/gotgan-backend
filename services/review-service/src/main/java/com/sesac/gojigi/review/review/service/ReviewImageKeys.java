package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 작성 · 수정 imageKeys 검사 — presign 이 발급한 내 키(reviews/{userId}/{UUID}.{jpg|png|webp})만, 최대 3장 · 중복 없음.
 * S3 에 실제로 올라갔는지는 보지 않는다(10/28 결정, product 와 같음) — 형식 · 크기는 presign 서명이, 이미지가 아닌 파일은 upload-guard 가 막는다.
 */
final class ReviewImageKeys {

    static final String PREFIX = "reviews/";
    static final int MAX_IMAGES = 3;

    private ReviewImageKeys() {
    }

    /** 사진 없음(null · 빈 목록)이면 빈 목록. 이미 붙어 있는 키(currentKeys)는 수정 때 그대로 다시 보낼 수 있다 */
    static List<String> check(Long userId, List<String> keys, Set<String> currentKeys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        if (keys.size() > MAX_IMAGES) {
            throw invalid("사진은 최대 " + MAX_IMAGES + "장까지 올릴 수 있습니다.");
        }
        Pattern ownKey = Pattern.compile(Pattern.quote(PREFIX + userId + "/") + "[A-Za-z0-9-]{1,64}\\.(jpg|png|webp)");
        if (keys.stream().anyMatch(key -> key == null || !(currentKeys.contains(key) || ownKey.matcher(key).matches()))) {
            throw invalid("업로드 주소 발급으로 받은 내 사진만 사용할 수 있습니다.");
        }
        if (new HashSet<>(keys).size() != keys.size()) {
            throw invalid("같은 사진이 중복되었습니다.");
        }
        return keys;
    }

    private static ReviewException invalid(String message) {
        return new ReviewException(ReviewErrorCode.REVIEW_IMAGE_INVALID, message);
    }
}
