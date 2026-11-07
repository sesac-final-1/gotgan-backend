package com.sesac.gojigi.admin.system;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.admin.TestcontainersConfiguration;
import com.sesac.gojigi.admin.system.client.KubernetesClient;
import com.sesac.gojigi.admin.system.client.KubernetesClient.Pod;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import com.sesac.gojigi.admin.system.service.SystemCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 장애 시연 (F-1) — 상태 · 내리기 · 타임라인. 쿠버네티스는 가짜, DB(admindb chaos_event)는 실제.
 * 재시도 제한이 서비스별이라 테스트마다 다른 서비스를 내린다 (같은 컨텍스트가 DB 를 공유)
 */
@SpringBootTest(properties = { "management.tracing.sampling.probability=0", "app.chaos.enabled=true" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSystemApiTest {

    private static final long ADMIN_ID = 1L;

    @Autowired MockMvc mockMvc;
    @MockitoBean KubernetesClient kubernetes;

    private final List<Pod> pods = new ArrayList<>();

    /** 기본 — 모든 서비스 레플리카 2 · 파드 2개 Ready */
    @BeforeEach
    void cluster() {
        pods.clear();
        SystemCatalog.SERVICES.forEach(t -> {
            pods.add(pod(t.name() + "-a", true, false));
            pods.add(pod(t.name() + "-b", true, false));
        });
        Map<String, Integer> desired = SystemCatalog.SERVICES.stream().collect(Collectors.toMap(SystemCatalog.Target::name, t -> 2));
        when(kubernetes.desiredReplicas()).thenReturn(desired);
        when(kubernetes.pods()).thenAnswer(inv -> List.copyOf(pods));
    }

    private static Pod pod(String name, boolean ready, boolean terminating) {
        String app = name.substring(0, name.lastIndexOf('-'));
        return new Pod(name, app, "ip-10-0-11-1", ready ? "Running" : "Pending", ready, 0, Instant.now().minusSeconds(30), terminating);
    }

    private void replace(String name, boolean ready, boolean terminating) {
        pods.removeIf(p -> p.name().equals(name));
        pods.add(pod(name, ready, terminating));
    }

    private ResultActions statusApi() throws Exception {
        return mockMvc.perform(get("/api/admin/system/status").header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN"));
    }

    private ResultActions kill(String service, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/system/services/{name}/kill", service).header("X-User-Id", ADMIN_ID)
                .header("X-User-Roles", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions events() throws Exception {
        return mockMvc.perform(get("/api/admin/system/events").header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN"));
    }

    @Test
    @DisplayName("상태 — 서비스 13개(화면 순서) · 상태 계산 · 내릴 수 있는지 · 기능은 의존 관계로")
    void status_calculates() throws Exception {
        pods.removeIf(p -> p.app().equals("order-service"));              // order 0/2 → DOWN
        replace("product-service-b", false, false);                       // product 1/2 (내린 적 없음) → DEGRADED
        replace("chat-service-b", true, true);                            // 지워지는 중인 파드는 ready 로 세지 않음 → DEGRADED

        statusApi().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chaosEnabled").value(true))
                .andExpect(jsonPath("$.data.serverTime").exists())
                .andExpect(jsonPath("$.data.services.length()").value(13))
                .andExpect(jsonPath("$.data.services[0].name").value("order-service"))
                .andExpect(jsonPath("$.data.services[0].state").value("DOWN"))
                .andExpect(jsonPath("$.data.services[0].ready").value(0))
                .andExpect(jsonPath("$.data.services[0].desired").value(2))
                .andExpect(jsonPath("$.data.services[0].killable").value(true))
                .andExpect(jsonPath("$.data.services[1].state").value("DEGRADED"))
                .andExpect(jsonPath("$.data.services[1].pods[1].phase").value("Pending"))
                .andExpect(jsonPath("$.data.services[1].pods[0].node").value("ip-10-0-11-1"))
                .andExpect(jsonPath("$.data.services[7].name").value("chat-service"))
                .andExpect(jsonPath("$.data.services[7].state").value("DEGRADED"))
                .andExpect(jsonPath("$.data.services[7].pods[1].terminating").value(true))
                .andExpect(jsonPath("$.data.services[8].name").value("gateway-service"))
                .andExpect(jsonPath("$.data.services[8].killable").value(false))
                .andExpect(jsonPath("$.data.services[8].state").value("UP"))
                .andExpect(jsonPath("$.data.features[?(@.key=='CART_ORDER')].state").value("DOWN"))
                .andExpect(jsonPath("$.data.features[3].key").value("CART_ORDER"))
                .andExpect(jsonPath("$.data.features[3].causes.length()").value(2))
                .andExpect(jsonPath("$.data.features[3].causes[0]").value("order-service"))
                .andExpect(jsonPath("$.data.features[3].causes[1]").value("product-service"))
                .andExpect(jsonPath("$.data.features[?(@.key=='BROWSE')].state").value("DEGRADED"))
                .andExpect(jsonPath("$.data.features[?(@.key=='REVIEW')].state").value("UP"))
                .andExpect(jsonPath("$.data.features[?(@.key=='CHAT')].state").value("DEGRADED"));
    }

    @Test
    @DisplayName("ADMIN 이 아니면 — 상태 · 내리기 · 타임라인 모두 403 FORBIDDEN")
    void nonAdmin_forbidden() throws Exception {
        mockMvc.perform(get("/api/admin/system/status").header("X-User-Id", 4L).header("X-User-Roles", "BUYER,SELLER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/admin/system/events").header("X-User-Id", 4L).header("X-User-Roles", "BUYER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/system/services/order-service/kill").header("X-User-Id", 4L).header("X-User-Roles", "BUYER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"ALL\"}"))
                .andExpect(status().isForbidden());
        verify(kubernetes, never()).deletePod(any());
    }

    @Test
    @DisplayName("ONE — Ready 파드 1개 삭제 202, 바로 다시 409, 새 파드 기동 중 RECOVERING, 다 뜨면 UP + 복구 시간 기록")
    void killOne_cooldown_recovery() throws Exception {
        kill("store-service", "{\"scope\":\"ONE\",\"reason\":\"시연 1\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.eventId").isNumber())
                .andExpect(jsonPath("$.data.deletedPods[0]").value("store-service-a"))
                .andExpect(jsonPath("$.data.deletedPods.length()").value(1));
        verify(kubernetes).deletePod("store-service-a");

        kill("store-service", "{\"scope\":\"ALL\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYSTEM_KILL_COOLDOWN"));

        replace("store-service-a", true, true);       // 지워지는 중
        pods.add(pod("store-service-c", false, false)); // 새 파드 기동 중
        statusApi().andExpect(jsonPath("$.data.services[3].name").value("store-service"))
                .andExpect(jsonPath("$.data.services[3].state").value("RECOVERING"))
                .andExpect(jsonPath("$.data.services[3].ready").value(1))
                .andExpect(jsonPath("$.data.features[?(@.key=='STORE')].state").value("DEGRADED"));
        events().andExpect(jsonPath("$.data[0].service").value("store-service"))
                .andExpect(jsonPath("$.data[0].recoveredAt").doesNotExist());

        replace("store-service-c", true, false);
        statusApi().andExpect(jsonPath("$.data.services[3].state").value("UP"));
        events().andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].service").value("store-service"))
                .andExpect(jsonPath("$.data[0].scope").value("ONE"))
                .andExpect(jsonPath("$.data[0].reason").value("시연 1"))
                .andExpect(jsonPath("$.data[0].actorId").value(ADMIN_ID))
                .andExpect(jsonPath("$.data[0].deletedPods[0]").value("store-service-a"))
                .andExpect(jsonPath("$.data[0].killedAt").exists())
                .andExpect(jsonPath("$.data[0].recoveredAt").exists())
                .andExpect(jsonPath("$.data[0].recoverySeconds").isNumber());
    }

    @Test
    @DisplayName("ALL — 지워지는 중이 아닌 파드 전부 삭제, 그 뒤 Ready 0 이면 DOWN · 기능 DOWN")
    void killAll() throws Exception {
        pods.add(pod("user-service-old", false, true));

        kill("user-service", "{\"scope\":\"ALL\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.deletedPods.length()").value(2));
        verify(kubernetes).deletePod("user-service-a");
        verify(kubernetes).deletePod("user-service-b");
        verify(kubernetes, never()).deletePod("user-service-old");

        replace("user-service-a", true, true);
        replace("user-service-b", true, true);
        pods.add(pod("user-service-c", false, false));
        statusApi().andExpect(jsonPath("$.data.services[4].name").value("user-service"))
                .andExpect(jsonPath("$.data.services[4].state").value("DOWN"))
                .andExpect(jsonPath("$.data.features[?(@.key=='MYPAGE')].state").value("DOWN"));
    }

    @Test
    @DisplayName("허용 목록 — gateway · admin · mysql · 없는 이름은 400 SYSTEM_SERVICE_NOT_KILLABLE")
    void notKillable() throws Exception {
        for (String name : List.of("gateway-service", "admin-service", "mysql", "keycloak", "redis", "unknown")) {
            kill(name, "{\"scope\":\"ALL\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SYSTEM_SERVICE_NOT_KILLABLE"));
        }
        verify(kubernetes, never()).deletePod(any());
    }

    @Test
    @DisplayName("Ready 파드가 없으면 ONE 은 409 SYSTEM_NO_READY_POD · scope 없으면 400")
    void noReadyPod_and_validation() throws Exception {
        replace("chat-service-a", false, false);
        replace("chat-service-b", false, false);
        kill("chat-service", "{\"scope\":\"ONE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYSTEM_NO_READY_POD"));
        kill("chat-service", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("쿠버네티스 조회 실패 — 상태 · 내리기 503 SYSTEM_CLUSTER_UNAVAILABLE, 타임라인은 그대로")
    void clusterUnavailable() throws Exception {
        when(kubernetes.pods()).thenThrow(new SystemException(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE));
        statusApi().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SYSTEM_CLUSTER_UNAVAILABLE"));
        kill("settlement-service", "{\"scope\":\"ALL\"}").andExpect(status().isServiceUnavailable());
        events().andExpect(status().isOk());
    }
}
