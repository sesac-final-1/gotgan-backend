package com.sesac.gojigi.review.common.util;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.web.util.HtmlUtils;

/**
 * 리뷰 · 문의 · 답글 본문 정화 (OWASP Java HTML Sanitizer, 컨벤션 XSS 방지). 본문은 일반 텍스트라 태그를 모두 지운다.
 * Sanitizer 가 글자를 HTML 엔티티로 바꾸므로(& → &amp;) 다시 되돌려 저장한다 — 화면은 React 가 이스케이프해 출력.
 */
public final class TextSanitizer {

    private static final PolicyFactory NO_TAGS = new HtmlPolicyBuilder().toFactory();

    private TextSanitizer() {
    }

    /** 태그를 지운 일반 텍스트, 남는 글자가 없으면 null */
    public static String plainText(String value) {
        if (value == null) {
            return null;
        }
        // 엔티티로 쓴 태그(&lt;b&gt;)는 되돌리면 태그가 되므로, 더 지울 것이 없을 때까지 반복한다
        String text = value;
        String previous;
        do {
            previous = text;
            text = HtmlUtils.htmlUnescape(NO_TAGS.sanitize(previous));
        } while (!text.equals(previous));
        text = text.strip();
        return text.isEmpty() ? null : text;
    }
}
