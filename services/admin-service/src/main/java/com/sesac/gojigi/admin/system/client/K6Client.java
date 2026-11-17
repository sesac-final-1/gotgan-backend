package com.sesac.gojigi.admin.system.client;

import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * k6 REST API — 실행 중 사용자 수 변경(PATCH /v1/status). externally-controlled 실행기 전용(k6 1.x, v2 에서 제거).
 * 파드 IP 로 직접 부른다(Service 로 노출하지 않음 — NetworkPolicy 업무 티어끼리만). 주소 형식은 app.loadtest.k6-api-url
 */
@Slf4j
@Component
public class K6Client {

    private final RestClient restClient;
    private final String apiUrl;

    public K6Client(RestClient.Builder restClientBuilder, @Value("${app.loadtest.k6-api-url}") String apiUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = restClientBuilder.clone().requestFactory(factory).build();
        this.apiUrl = apiUrl;
    }

    /** 사용자 수를 vus 로 — 0 이면 요청을 멈춘 채 대기(Job 은 그대로). 실패면 503 SYSTEM_LOADTEST_CONTROL_FAILED */
    public void scale(String podIp, int vus) {
        Map<String, Object> body = Map.of("data", Map.of("type", "status", "id", "default", "attributes", Map.of("vus", vus)));
        try {
            restClient.patch().uri(apiUrl + "/v1/status", podIp).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("k6 사용자 수 변경 실패 - pod={}, vus={}, {}", podIp, vus, e.getMessage());
            throw new SystemException(SystemErrorCode.SYSTEM_LOADTEST_CONTROL_FAILED, e);
        }
    }

    /** 정상 종료 — k6 가 요청을 멈추고 끝나 Job 이 완료로 남는다(결과 화면 유지, 11/17). 실패하면 false(호출한 쪽이 Job 을 지운다) */
    public boolean stop(String podIp) {
        Map<String, Object> body = Map.of("data", Map.of("type", "status", "id", "default", "attributes", Map.of("stopped", true)));
        try {
            restClient.patch().uri(apiUrl + "/v1/status", podIp).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("k6 정상 종료 실패 - pod={}, {}", podIp, e.getMessage());
            return false;
        }
    }
}
