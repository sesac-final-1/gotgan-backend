package com.sesac.gojigi.payment.payment.client;

import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import com.sesac.gojigi.payment.payment.exception.PaymentException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 포트원 V2 웹훅 서명 검증 — Standard Webhooks 스펙 (포트원 개발자 문서 "웹훅 연동하기").
 * 서명 = base64(HMAC-SHA256(key, "{webhook-id}.{webhook-timestamp}.{원문 본문}")), key = 시크릿의 "whsec_" 뒤 base64 디코딩.
 * webhook-signature 헤더는 "v1,{서명}" 을 공백으로 여러 개 담을 수 있다(시크릿 교체 중). 시각이 허용 범위 밖이면 재전송 공격으로 보고 거절.
 */
@Slf4j
@Component
public class PortOneWebhookVerifier {

    private static final String SECRET_PREFIX = "whsec_";
    private static final String SIGNATURE_VERSION = "v1,";

    private final byte[] key;
    private final Duration tolerance;
    private final Clock clock;

    @Autowired
    public PortOneWebhookVerifier(@Value("${app.portone.webhook-secret:}") String secret,
            @Value("${app.portone.webhook-tolerance:5m}") Duration tolerance) {
        this(secret, tolerance, Clock.systemUTC());
    }

    PortOneWebhookVerifier(String secret, Duration tolerance, Clock clock) {
        this.key = decodeKey(secret);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String webhookId, String webhookTimestamp, String webhookSignature, String body) {
        if (key.length == 0 || isBlank(webhookId) || isBlank(webhookTimestamp) || isBlank(webhookSignature) || body == null) {
            throw invalid("필수 헤더 · 시크릿 없음");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(webhookTimestamp.trim());
        } catch (NumberFormatException e) {
            throw invalid("timestamp 형식 오류");
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs().compareTo(tolerance) > 0) {
            throw invalid("허용 시각 초과");
        }
        byte[] expected = sign(webhookId + "." + webhookTimestamp + "." + body);
        for (String candidate : webhookSignature.trim().split(" ")) {
            if (candidate.startsWith(SIGNATURE_VERSION) && matches(expected, candidate.substring(SIGNATURE_VERSION.length()))) {
                return;
            }
        }
        throw invalid("서명 불일치");
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 초기화 실패", e);
        }
    }

    private static boolean matches(byte[] expected, String base64Signature) {
        try {
            return MessageDigest.isEqual(expected, Base64.getDecoder().decode(base64Signature));   // 시간 일정 비교
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] decodeKey(String secret) {
        if (isBlank(secret)) {
            return new byte[0];
        }
        String raw = secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret;
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            return raw.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static PaymentException invalid(String why) {
        log.warn("포트원 웹훅 서명 검증 실패 - {}", why);
        return new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
