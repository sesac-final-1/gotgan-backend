package com.sesac.gojigi.admin.system.service;

import com.sesac.gojigi.admin.system.dto.response.ServiceState;
import com.sesac.gojigi.admin.system.dto.response.SystemResponses.FeatureStatus;
import java.util.List;
import java.util.Map;

/**
 * 화면에 보이는 서비스 · 사용자 기능과 의존 관계 (협업 요청 문서 6장 · 11/6 QA 장애 영향표로 확정).
 * 서비스 이름 = 파드 라벨 app = Grafana app 라벨
 */
public final class SystemCatalog {

    private SystemCatalog() {
    }

    public record Target(String name, boolean killable) {
    }

    public record Feature(String key, String label, List<String> dependsOn) {
    }

    /** 화면 순서. 입구 · 이 화면 자체 · 로그인 · 데이터는 내리면 시연이 끝나 버려 내릴 수 없음 */
    public static final List<Target> SERVICES = List.of(
            new Target("order-service", true),
            new Target("product-service", true),
            new Target("payment-service", true),
            new Target("store-service", true),
            new Target("user-service", true),
            new Target("review-service", true),
            new Target("settlement-service", true),
            new Target("chat-service", true),
            new Target("gateway-service", false),
            new Target("admin-service", false),
            new Target("keycloak", false),
            new Target("mysql", false),
            new Target("redis", false));

    /** 11/6 QA 실측 — 장바구니가 상품 · 스토어 배송 정책을 읽고, 결제는 주문 안의 사가가 시작한다 */
    public static final List<Feature> FEATURES = List.of(
            new Feature("BROWSE", "둘러보기 · 상품 상세", List.of("product-service")),
            new Feature("STORE", "스토어", List.of("store-service")),
            new Feature("REVIEW", "리뷰 · 문의", List.of("review-service")),
            new Feature("CART_ORDER", "장바구니 · 주문", List.of("order-service", "product-service", "store-service")),
            new Feature("PAYMENT", "결제", List.of("order-service", "payment-service")),
            new Feature("CHAT", "채팅", List.of("chat-service")),
            new Feature("SETTLEMENT", "판매자 정산", List.of("settlement-service")),
            new Feature("MYPAGE", "회원 · 마이페이지", List.of("user-service")));

    /**
     * 서비스 상태 — Ready 0개면 DOWN(새 파드가 뜨는 중이어도 응답은 못 함), 원하는 수만큼이면 UP,
     * 그 사이는 내린 뒤 복구 전이면 RECOVERING · 아니면 DEGRADED
     */
    public static ServiceState serviceState(int desired, int ready, boolean recovering) {
        if (ready == 0) {
            return ServiceState.DOWN;
        }
        if (ready >= desired) {
            return ServiceState.UP;
        }
        return recovering ? ServiceState.RECOVERING : ServiceState.DEGRADED;
    }

    /** 기능 상태 — 원인 서비스 중 하나라도 DOWN 이면 DOWN, DEGRADED · RECOVERING 이면 DEGRADED. 목록에 없는 서비스는 DOWN 으로 본다 */
    public static List<FeatureStatus> features(Map<String, ServiceState> states) {
        return FEATURES.stream().map(f -> {
            List<String> causes = f.dependsOn().stream().filter(s -> states.getOrDefault(s, ServiceState.DOWN) != ServiceState.UP).toList();
            boolean down = causes.stream().anyMatch(s -> states.getOrDefault(s, ServiceState.DOWN) == ServiceState.DOWN);
            ServiceState state = down ? ServiceState.DOWN : causes.isEmpty() ? ServiceState.UP : ServiceState.DEGRADED;
            return new FeatureStatus(f.key(), f.label(), state, causes);
        }).toList();
    }
}
