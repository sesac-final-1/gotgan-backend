package com.sesac.gojigi.admin.system.client;

import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * 쿠버네티스 REST API 직접 호출 (라이브러리 없이 RestClient) — 주소 · 인증서 · 토큰은 KubernetesClientConfig 가 붙인다.
 * 권한은 Role admin-service-chaos(gotgan-config chaos-rbac.yaml): pods get · list · delete, deployments · statefulsets get · list
 */
@Slf4j
@RequiredArgsConstructor
public class InClusterKubernetesClient implements KubernetesClient {

    private final RestClient restClient;
    private final String namespace;

    @Override
    public Map<String, Integer> desiredReplicas() {
        Map<String, Integer> desired = new HashMap<>();
        for (String kind : List.of("deployments", "statefulsets")) {
            JsonNode list = call(() -> restClient.get().uri("/apis/apps/v1/namespaces/{ns}/" + kind, namespace)
                    .retrieve().body(JsonNode.class));
            for (JsonNode item : list.path("items")) {
                desired.put(item.path("metadata").path("name").asString(), item.path("spec").path("replicas").asInt(1));
            }
        }
        return desired;
    }

    @Override
    public List<Pod> pods() {
        JsonNode list = call(() -> restClient.get().uri("/api/v1/namespaces/{ns}/pods?labelSelector=app", namespace)
                .retrieve().body(JsonNode.class));
        List<Pod> pods = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            pods.add(pod(item));
        }
        return pods;
    }

    @Override
    public void deletePod(String podName) {
        try {
            // 즉시 삭제(유예 0) — 기본 유예면 옛 파드가 preStop 10초 동안 계속 응답해(준비된 파드가 없으면 종료 중 파드로 보냄)
            // 화면이 실패하는 구간이 8초 안팎뿐이라 시연에서 멈춘 장면이 안 보임(11/10 chat 확인). 처리 중 요청은 끊겨도 되는 시연용 기능
            call(() -> restClient.delete().uri("/api/v1/namespaces/{ns}/pods/{name}?gracePeriodSeconds=0", namespace, podName).retrieve().toBodilessEntity());
        } catch (SystemException e) {
            if (!(e.getCause() instanceof HttpClientErrorException.NotFound)) {
                throw e;
            }
            log.info("이미 없는 파드 - pod={}", podName);   // 그 사이 지워짐 — 목적은 이룸
        }
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("쿠버네티스 API 호출 실패 - {}", e.getMessage());   // 클러스터 밖(로컬) · 권한 없음(403) · 타임아웃
            throw new SystemException(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE, e);
        }
    }

    static Pod pod(JsonNode item) {
        JsonNode meta = item.path("metadata");
        JsonNode status = item.path("status");
        boolean ready = false;
        for (JsonNode condition : status.path("conditions")) {
            if ("Ready".equals(condition.path("type").asString()) && "True".equals(condition.path("status").asString())) {
                ready = true;
            }
        }
        int restarts = 0;
        for (JsonNode container : status.path("containerStatuses")) {
            restarts += container.path("restartCount").asInt(0);
        }
        String created = meta.path("creationTimestamp").asString(null);
        return new Pod(meta.path("name").asString(), meta.path("labels").path("app").asString(null),
                item.path("spec").path("nodeName").asString(null), status.path("phase").asString(null), ready, restarts,
                created == null ? null : Instant.parse(created), meta.hasNonNull("deletionTimestamp"));
    }
}
