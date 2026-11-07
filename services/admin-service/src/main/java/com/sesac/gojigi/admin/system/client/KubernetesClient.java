package com.sesac.gojigi.admin.system.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 쿠버네티스 API — sesac-gojigi 네임스페이스만. 실패(연결 · 권한 · 클러스터 밖)는 503 SYSTEM_CLUSTER_UNAVAILABLE.
 * 테스트는 이 인터페이스를 가짜로 바꾼다
 */
public interface KubernetesClient {

    /** Deployment · StatefulSet 이름 → 원하는 레플리카 수(spec.replicas) */
    Map<String, Integer> desiredReplicas();

    /** app 라벨이 있는 파드 전부 */
    List<Pod> pods();

    /** 파드 삭제 — Deployment · StatefulSet 이 새 파드를 만든다. 이미 없으면(404) 무시 */
    void deletePod(String podName);

    record Pod(String name, String app, String node, String phase, boolean ready, int restarts, Instant createdAt, boolean terminating) {
    }
}
