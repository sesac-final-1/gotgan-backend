package com.sesac.gojigi.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 지표 접속 허용 대역 — 운영 기본값(application.yml)과 같은 목록 */
class MetricsAccessManagerTest {

    private final MetricsAccessManager manager =
            new MetricsAccessManager(List.of("127.0.0.1/32", "::1/128", " 10.0.11.0/24", "10.0.12.0/24"));

    private static InetSocketAddress from(String ip) {
        return new InetSocketAddress(ip, 40000);
    }

    @Test
    @DisplayName("Prometheus 파드 · 노드(프라이빗 서브넷 2a · 2c) · localhost 는 허용")
    void privateSubnetsAndLocalhost_allowed() {
        assertThat(manager.isAllowed(from("10.0.11.206"))).isTrue();
        assertThat(manager.isAllowed(from("10.0.12.94"))).isTrue();
        assertThat(manager.isAllowed(from("127.0.0.1"))).isTrue();
        assertThat(manager.isAllowed(from("::1"))).isTrue();
    }

    @Test
    @DisplayName("NLB(퍼블릭 서브넷)로 들어온 외부 요청 · 공인 IP · 그 밖 VPC 대역 · 접속 IP 모름은 거절")
    void nlbAndPublic_denied() {
        assertThat(manager.isAllowed(from("10.0.1.211"))).isFalse();
        assertThat(manager.isAllowed(from("10.0.2.6"))).isFalse();
        assertThat(manager.isAllowed(from("203.0.113.7"))).isFalse();
        assertThat(manager.isAllowed(from("10.0.13.5"))).isFalse();
        assertThat(manager.isAllowed(null)).isFalse();
        assertThat(manager.isAllowed(InetSocketAddress.createUnresolved("api.gotgan.click", 443))).isFalse();
    }

    @Test
    @DisplayName("CIDR 형식이 아니면 기동 실패 — 잘못된 설정을 조용히 넘기지 않는다")
    void invalidCidr_failsFast() {
        assertThatThrownBy(() -> new MetricsAccessManager(List.of("10.0.11.0")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
