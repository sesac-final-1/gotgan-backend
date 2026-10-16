package com.sesac.gojigi.payment.payment.client;

/** 통합 테스트에서 포트원 대신 웹훅 서명을 만든다 — 키는 application-test.yml 의 webhook-secret 과 같다 */
public final class PortOneWebhookVerifierTestSupport {

    private PortOneWebhookVerifierTestSupport() {
    }

    public static String sign(String webhookId, long timestamp, String body) throws Exception {
        return PortOneWebhookVerifierTest.sign(PortOneWebhookVerifierTest.KEY, webhookId, timestamp, body);
    }
}
