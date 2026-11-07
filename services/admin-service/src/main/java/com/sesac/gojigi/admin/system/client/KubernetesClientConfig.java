package com.sesac.gojigi.admin.system.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * in-cluster 쿠버네티스 API 클라이언트 — 파드에 마운트된 ServiceAccount 의 ca.crt(서버 인증서 확인) · token(Bearer).
 * 클러스터 밖(로컬 · 테스트)에는 파일이 없어 호출이 실패하고 503 으로 응답한다 — 기동은 막지 않는다
 */
@Configuration
public class KubernetesClientConfig {

    @Bean
    public KubernetesClient kubernetesClient(RestClient.Builder restClientBuilder,
            @Value("${app.chaos.kubernetes-url}") String baseUrl,
            @Value("${app.chaos.namespace}") String namespace,
            @Value("${app.chaos.service-account-dir}") Path serviceAccountDir,
            @Value("${app.client.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.client.read-timeout:3s}") Duration readTimeout) {
        HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(connectTimeout);
        Path ca = serviceAccountDir.resolve("ca.crt");
        if (Files.isReadable(ca)) {
            http.sslContext(sslContext(ca));
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(readTimeout);
        RestClient restClient = restClientBuilder.clone().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(bearerToken(serviceAccountDir.resolve("token"))).build();
        return new InClusterKubernetesClient(restClient, namespace);
    }

    /** 토큰은 kubelet 이 주기적으로 갈아 끼운다 — 요청마다 파일에서 읽는다. 파일이 없으면 IOException → 503 */
    static ClientHttpRequestInterceptor bearerToken(Path tokenFile) {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(Files.readString(tokenFile).strip());
            return execution.execute(request, body);
        };
    }

    /** 클러스터 CA 만 믿는 SSLContext */
    static SSLContext sslContext(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trust.setCertificateEntry("k8s-ca-" + i++, cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return ssl;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("쿠버네티스 CA 인증서를 읽지 못했습니다: " + caFile, e);
        }
    }
}
