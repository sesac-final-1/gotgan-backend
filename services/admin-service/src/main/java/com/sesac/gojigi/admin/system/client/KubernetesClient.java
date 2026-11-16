package com.sesac.gojigi.admin.system.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** 부하테스트 Job(라벨 gotgan.loadtest/id 가 있는 것) — 순서 없음 */
    List<LoadJob> loadJobs();

    /** Job 생성 — manifest 는 batch/v1 Job 본문 그대로 */
    void createJob(Map<String, Object> manifest);

    /** Job 삭제(파드까지, 백그라운드). 이미 없으면(404) 무시 */
    void deleteJob(String name);

    /** 부하테스트 Job 의 Running 파드 IP — k6 REST API(6565) 호출용. 아직 없으면 빈 Optional */
    Optional<String> loadJobPodIp(String loadTestId);

    /** kind = load(부하) · accounts(계정 준비). scenario · vus · durationSec 은 Job 어노테이션(시작할 때 값) */
    record LoadJob(String name, String id, String kind, String scenario, int vus, int durationSec, Instant createdAt,
                   int active, int succeeded, int failed) {
    }

    record Pod(String name, String app, String node, String phase, boolean ready, int restarts, Instant createdAt, boolean terminating) {
    }
}
