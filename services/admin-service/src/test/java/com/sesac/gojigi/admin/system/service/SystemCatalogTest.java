package com.sesac.gojigi.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.admin.system.dto.response.ServiceState;
import com.sesac.gojigi.admin.system.dto.response.SystemResponses.FeatureStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SystemCatalogTest {

    @Test
    @DisplayName("서비스 상태 — Ready 0 = DOWN, 원하는 수 이상 = UP, 그 사이는 내린 뒤면 RECOVERING · 아니면 DEGRADED")
    void serviceState_rules() {
        assertThat(SystemCatalog.serviceState(2, 0, true)).isEqualTo(ServiceState.DOWN);
        assertThat(SystemCatalog.serviceState(2, 0, false)).isEqualTo(ServiceState.DOWN);
        assertThat(SystemCatalog.serviceState(0, 0, false)).isEqualTo(ServiceState.DOWN);
        assertThat(SystemCatalog.serviceState(2, 2, false)).isEqualTo(ServiceState.UP);
        assertThat(SystemCatalog.serviceState(1, 2, true)).isEqualTo(ServiceState.UP);
        assertThat(SystemCatalog.serviceState(2, 1, true)).isEqualTo(ServiceState.RECOVERING);
        assertThat(SystemCatalog.serviceState(2, 1, false)).isEqualTo(ServiceState.DEGRADED);
    }

    private static Map<String, ServiceState> allUp() {
        return SystemCatalog.SERVICES.stream().collect(Collectors.toMap(SystemCatalog.Target::name, t -> ServiceState.UP,
                (a, b) -> a, HashMap::new));
    }

    private static Map<String, FeatureStatus> byKey(List<FeatureStatus> features) {
        return features.stream().collect(Collectors.toMap(FeatureStatus::key, Function.identity()));
    }

    @Test
    @DisplayName("기능 상태 — 전부 UP 이면 8개 기능 모두 UP · causes 비어 있음")
    void features_allUp() {
        List<FeatureStatus> features = SystemCatalog.features(allUp());
        assertThat(features).hasSize(8).allSatisfy(f -> {
            assertThat(f.state()).isEqualTo(ServiceState.UP);
            assertThat(f.causes()).isEmpty();
        });
    }

    @Test
    @DisplayName("기능 상태 — order DOWN 이면 장바구니 · 주문 · 결제 DOWN, 둘러보기 · 리뷰 · 채팅은 UP")
    void features_orderDown() {
        Map<String, ServiceState> states = allUp();
        states.put("order-service", ServiceState.DOWN);
        Map<String, FeatureStatus> f = byKey(SystemCatalog.features(states));
        assertThat(f.get("CART_ORDER").state()).isEqualTo(ServiceState.DOWN);
        assertThat(f.get("CART_ORDER").causes()).containsExactly("order-service");
        assertThat(f.get("PAYMENT").state()).isEqualTo(ServiceState.DOWN);
        assertThat(f.get("BROWSE").state()).isEqualTo(ServiceState.UP);
        assertThat(f.get("REVIEW").state()).isEqualTo(ServiceState.UP);
        assertThat(f.get("CHAT").state()).isEqualTo(ServiceState.UP);
    }

    @Test
    @DisplayName("기능 상태 — DOWN 이 하나라도 있으면 DOWN, DEGRADED · RECOVERING 만 있으면 DEGRADED · causes 에 모두")
    void features_mixed() {
        Map<String, ServiceState> states = allUp();
        states.put("product-service", ServiceState.RECOVERING);
        states.put("store-service", ServiceState.DOWN);
        states.put("payment-service", ServiceState.DEGRADED);
        Map<String, FeatureStatus> f = byKey(SystemCatalog.features(states));
        assertThat(f.get("CART_ORDER").state()).isEqualTo(ServiceState.DOWN);
        assertThat(f.get("CART_ORDER").causes()).containsExactly("product-service", "store-service");
        assertThat(f.get("BROWSE").state()).isEqualTo(ServiceState.DEGRADED);
        assertThat(f.get("PAYMENT").state()).isEqualTo(ServiceState.DEGRADED);
        assertThat(f.get("PAYMENT").causes()).containsExactly("payment-service");
    }

    @Test
    @DisplayName("기능 상태 — 상태를 모르는 서비스는 DOWN 으로 본다")
    void features_unknownIsDown() {
        Map<String, FeatureStatus> f = byKey(SystemCatalog.features(Map.of()));
        assertThat(f.get("MYPAGE").state()).isEqualTo(ServiceState.DOWN);
        assertThat(f.get("MYPAGE").causes()).containsExactly("user-service");
    }

    @Test
    @DisplayName("내릴 수 없는 서비스 — gateway · admin · keycloak · mysql · redis")
    void services_notKillable() {
        assertThat(SystemCatalog.SERVICES.stream().filter(t -> !t.killable()).map(SystemCatalog.Target::name))
                .containsExactly("gateway-service", "admin-service", "keycloak", "mysql", "redis");
        assertThat(SystemCatalog.SERVICES.stream().filter(SystemCatalog.Target::killable)).hasSize(8);
    }
}
