package com.sesac.gojigi.store.common.html;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

/**
 * 스토어 소개 HTML 정화 — 입점 신청(2절)과 판매자 스토어 설정(13절)이 같은 정책을 쓴다.
 * 글자 서식 · 문단 · 링크만 남기고 script · style · on* 속성은 지운다. 화면은 safeHtml 로 한 번 더(프론트 규칙)
 */
public final class IntroHtml {

    private static final PolicyFactory POLICY = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS).and(Sanitizers.LINKS);

    private IntroHtml() {
    }

    /** 비었거나 정화 뒤 남는 게 없으면 null */
    public static String sanitize(String intro) {
        if (intro == null || intro.isBlank()) {
            return null;
        }
        String sanitized = POLICY.sanitize(intro.trim());
        return sanitized.isBlank() ? null : sanitized.trim();
    }
}
