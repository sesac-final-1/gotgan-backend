package com.sesac.gojigi.gateway.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.zipkin.autoconfigure.ZipkinProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * application-k8s.yml 의 Zipkin 주소 검증 — 9개 서비스 같은 테스트 (관측성 담당 박지연). yml 을 바꾸면 함께 고칠 것.
 * Boot 4 는 옛 키 management.zipkin.tracing.endpoint 를 무시하고 기본값 localhost:9411 로 보내 EKS 트레이스가 0건이었다.
 * Spring 컨텍스트 없이 실제 yml 을 Boot 의 ZipkinProperties 에 바인딩해 확인한다.
 */
class ZipkinConfigTest {

    /** Boot 4 ZipkinProperties 의 바인딩 접두어 */
    private static final String PREFIX = "management.tracing.export.zipkin";
    /** gotgan-config deploy/k8s/01-configmap.yaml ZIPKIN_ENDPOINT 와 같은 값 — 바꾸면 함께 */
    private static final String CLUSTER_ENDPOINT = "http://zipkin.monitoring.svc.cluster.local:9411/api/v2/spans";

    @Test
    @DisplayName("k8s 프로필: ZIPKIN_ENDPOINT 가 없으면 클러스터 Zipkin 주소로 보낸다 (localhost 기본값 아님)")
    void k8sProfile_withoutEnv_bindsClusterEndpoint() throws IOException {
        MockEnvironment env = k8sEnvironment();

        assertThat(bindZipkin(env).getEndpoint()).isEqualTo(CLUSTER_ENDPOINT);
    }

    @Test
    @DisplayName("k8s 프로필: ZIPKIN_ENDPOINT 환경변수가 있으면 그 주소로 덮어쓴다")
    void k8sProfile_withEnv_bindsOverriddenEndpoint() throws IOException {
        MockEnvironment env = k8sEnvironment();
        env.setProperty("ZIPKIN_ENDPOINT", "http://zipkin.other:9411/api/v2/spans");

        assertThat(bindZipkin(env).getEndpoint()).isEqualTo("http://zipkin.other:9411/api/v2/spans");
    }

    @Test
    @DisplayName("k8s 프로필: Boot 4 에서 무시되는 옛 키 management.zipkin.tracing.* 를 쓰지 않는다")
    void k8sProfile_doesNotUseRemovedZipkinKey() throws IOException {
        MockEnvironment env = k8sEnvironment();

        assertThat(env.containsProperty("management.zipkin.tracing.endpoint")).isFalse();
    }

    private static MockEnvironment k8sEnvironment() throws IOException {
        MockEnvironment env = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load("application-k8s.yml", new ClassPathResource("application-k8s.yml"))
                .forEach(env.getPropertySources()::addLast);
        return env;
    }

    private static ZipkinProperties bindZipkin(MockEnvironment env) {
        return Binder.get(env).bindOrCreate(PREFIX, ZipkinProperties.class);
    }
}
