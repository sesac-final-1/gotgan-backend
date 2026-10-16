package com.sesac.gojigi.payment.payment.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortOneWebhookVerifierTest {

    static final byte[] KEY = "test-webhook-secret-key-32-bytes".getBytes(StandardCharsets.UTF_8);
    static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final long NOW = 1_792_000_000L;
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-1\"}}";

    private final PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(SECRET, Duration.ofMinutes(5),
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    /** Standard Webhooks 서명 — 테스트 · 통합 테스트에서 포트원 대신 만든다 */
    static String sign(byte[] key, String id, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(
                mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("올바른 서명 · 허용 시각 안이면 통과 (시크릿 교체 중 여러 서명이 와도 하나만 맞으면 됨)")
    void verify_validSignature_passes() throws Exception {
        String signature = sign(KEY, "msg-1", NOW, BODY);

        assertThatCode(() -> verifier.verify("msg-1", String.valueOf(NOW), signature, BODY)).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify("msg-1", String.valueOf(NOW), "v1,AAAA " + signature, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("본문이 바뀌었거나 다른 시크릿으로 서명했으면 거절")
    void verify_tamperedBodyOrWrongSecret_rejected() throws Exception {
        String signature = sign(KEY, "msg-1", NOW, BODY);
        assertThatThrownBy(() -> verifier.verify("msg-1", String.valueOf(NOW), signature, BODY.replace("pay-1", "pay-2")))
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);

        String forged = sign("other-secret".getBytes(StandardCharsets.UTF_8), "msg-1", NOW, BODY);
        assertThatThrownBy(() -> verifier.verify("msg-1", String.valueOf(NOW), forged, BODY))
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);
    }

    @Test
    @DisplayName("허용 시각(5분)을 넘긴 메시지 · 헤더 누락은 거절 (재전송 공격 방지)")
    void verify_expiredTimestampOrMissingHeader_rejected() throws Exception {
        long old = NOW - 301;
        String signature = sign(KEY, "msg-1", old, BODY);
        assertThatThrownBy(() -> verifier.verify("msg-1", String.valueOf(old), signature, BODY))
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);
        assertThatThrownBy(() -> verifier.verify(null, String.valueOf(NOW), signature, BODY))
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);
    }

    @Test
    @DisplayName("시크릿이 설정되지 않았으면 모든 웹훅을 거절")
    void verify_noSecret_rejectsAll() throws Exception {
        PortOneWebhookVerifier noSecret = new PortOneWebhookVerifier("", Duration.ofMinutes(5), Clock.systemUTC());
        assertThatThrownBy(() -> noSecret.verify("msg-1", String.valueOf(NOW), sign(KEY, "msg-1", NOW, BODY), BODY))
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);
    }
}
